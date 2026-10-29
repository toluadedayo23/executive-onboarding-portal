package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.auth.AuthService;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.notification.PushSender.Result;
import ng.risktech.execplatform.user.User;
import ng.risktech.execplatform.vetting.VettingService;
import ng.risktech.execplatform.vetting.VettingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PushNotificationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = gen.generateKeyPair();
        r.add("app.push.vapid-public-key", () -> b64(uncompressed((ECPublicKey) pair.getPublic())));
        r.add("app.push.vapid-private-key", () -> b64(fixed32(((ECPrivateKey) pair.getPrivate()).getS().toByteArray())));
        r.add("app.push.vapid-subject", () -> "mailto:test@example.test");
    }

    @TestConfiguration
    static class FakeSenderConfig {
        @Bean
        @Primary
        FakeSender fakeSender() {
            return new FakeSender();
        }
    }

    static class FakeSender implements PushSender {
        final List<UUID> sentTo = new CopyOnWriteArrayList<>();
        volatile Result nextResult = Result.OK;

        @Override
        public Result send(Target target, byte[] payload) {
            sentTo.add(target.id());
            return nextResult;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired VettingService vetting;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired FakeSender sender;

    @BeforeEach
    void reset() {
        sender.sentTo.clear();
        sender.nextResult = Result.OK;
    }

    @Test
    void executiveCanSubscribeIdempotentlyAndUnsubscribeButAConsultantCannot() throws Exception {
        User exec = auth.create("push-exec1@example.test", "VaultPushExec1", "EXECUTIVE");
        User consultant = auth.create("push-con1@example.test", "VaultPushCon1", "CONSULTANT");
        String endpoint = "https://fcm.googleapis.com/fcm/send/synthetic-1";

        mvc.perform(as(get("/api/push/public-key"), exec)).andExpect(status().isOk());
        mvc.perform(as(post("/api/push/subscriptions"), exec).content(subscription(endpoint))).andExpect(status().isCreated());
        mvc.perform(as(post("/api/push/subscriptions"), exec).content(subscription(endpoint))).andExpect(status().isOk());
        assertThat(rowsFor(exec.getId())).isEqualTo(1);

        mvc.perform(as(post("/api/push/subscriptions"), consultant).content(subscription(endpoint))).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/push/subscriptions"), exec).content(subscription("https://evil.example.com/x")))
                .andExpect(status().isBadRequest());

        mvc.perform(as(delete("/api/push/subscriptions"), exec).content("{\"endpoint\":\"" + endpoint + "\"}")).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/push/subscriptions"), exec).content("{\"endpoint\":\"" + endpoint + "\"}")).andExpect(status().isNoContent());
        assertThat(rowsFor(exec.getId())).isZero();
        Integer audited = db.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('PUSH_SUBSCRIBED','PUSH_UNSUBSCRIBED') "
                + "AND entity_id = ? AND detail::text NOT LIKE '%fcm.googleapis%'", Integer.class, exec.getId().toString());
        assertThat(audited).isEqualTo(2);
    }

    @Test
    void notifiesOnlyAfterCommitAndNeverForARollbackOrAnInactiveUser() throws Exception {
        User exec = auth.create("push-exec2@example.test", "VaultPushExec2", "EXECUTIVE");
        User consultant = auth.create("push-con2@example.test", "VaultPushCon2", "CONSULTANT");
        UUID subscription = subscribe(exec, "https://fcm.googleapis.com/fcm/send/synthetic-2");
        UUID caseId = caseUnderReview(exec, consultant);

        tx.executeWithoutResult(status -> {
            vetting.transition(principal(consultant), caseId, VettingStatus.VERIFIED, null);
            status.setRollbackOnly();
        });
        Thread.sleep(500);
        assertThat(sender.sentTo).isEmpty();
        assertThat(db.queryForObject("SELECT status FROM vetting_cases WHERE id = ?", String.class, caseId)).isEqualTo("UNDER_REVIEW");

        tx.executeWithoutResult(status -> vetting.transition(principal(consultant), caseId, VettingStatus.VERIFIED, null));
        await(() -> sender.sentTo.size() == 1);
        assertThat(sender.sentTo).containsExactly(subscription);

        User inactive = auth.create("push-exec3@example.test", "VaultPushExec3", "EXECUTIVE");
        subscribe(inactive, "https://fcm.googleapis.com/fcm/send/synthetic-3");
        UUID otherCase = caseUnderReview(inactive, consultant);
        db.update("UPDATE users SET active = false WHERE id = ?", inactive.getId());
        tx.executeWithoutResult(status -> vetting.transition(principal(consultant), otherCase, VettingStatus.REJECTED, "Synthetic reason"));
        Thread.sleep(500);
        assertThat(sender.sentTo).hasSize(1);
    }

    @Test
    void aGoneSubscriptionIsRemoved() throws Exception {
        User exec = auth.create("push-exec4@example.test", "VaultPushExec4", "EXECUTIVE");
        User consultant = auth.create("push-con4@example.test", "VaultPushCon4", "CONSULTANT");
        subscribe(exec, "https://fcm.googleapis.com/fcm/send/synthetic-4");
        UUID caseId = caseUnderReview(exec, consultant);
        sender.nextResult = Result.GONE;

        tx.executeWithoutResult(status -> vetting.transition(principal(consultant), caseId, VettingStatus.MORE_INFO_REQUIRED, "Synthetic"));

        await(() -> rowsFor(exec.getId()) == 0);
        assertThat(sender.sentTo).hasSize(1);
    }

    private UUID subscribe(User exec, String endpoint) throws Exception {
        mvc.perform(as(post("/api/push/subscriptions"), exec).content(subscription(endpoint))).andExpect(status().isCreated());
        return db.queryForObject("SELECT id FROM push_subscriptions WHERE endpoint = ?", UUID.class, endpoint);
    }

    private UUID caseUnderReview(User exec, User consultant) {
        db.update("INSERT INTO executive_profiles(user_id) VALUES (?)", exec.getId());
        UUID caseId = UUID.randomUUID();
        db.update("INSERT INTO vetting_cases(id, profile_id, status, assigned_consultant_id, submitted_at) "
                + "VALUES (?, (SELECT id FROM executive_profiles WHERE user_id = ?), 'UNDER_REVIEW', ?, now())",
                caseId, exec.getId(), consultant.getId());
        return caseId;
    }

    private int rowsFor(UUID userId) {
        Integer n = db.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        var token = new UsernamePasswordAuthenticationToken(principal(user), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        return request.with(authentication(token)).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }

    private static AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }

    private static String subscription(String endpoint) {
        byte[] key = new byte[65];
        key[0] = 0x04;
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + b64(key) + "\",\"auth\":\"" + b64(new byte[16]) + "\"}}";
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Condition not met within 5s");
            Thread.sleep(50);
        }
    }

    private static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(fixed32(key.getW().getAffineX().toByteArray()), 0, out, 1, 32);
        System.arraycopy(fixed32(key.getW().getAffineY().toByteArray()), 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed32(byte[] value) {
        byte[] out = new byte[32];
        int copy = Math.min(32, value.length);
        System.arraycopy(value, value.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
