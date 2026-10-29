package ng.risktech.execplatform.onboarding;

import ng.risktech.execplatform.auth.AuthService;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;

    @Test
    void theSameRequestWithTheSameKeyIsAppliedOnceAndReplayedIdentically() throws Exception {
        User exec = executive("idem-1@example.test");
        String key = UUID.randomUUID().toString();
        String body = profile(1, "Ada Synthetic");

        MvcResult first = mvc.perform(save(exec, key, body)).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay")).andReturn();
        MvcResult second = mvc.perform(save(exec, key, body)).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true")).andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(version(exec)).isEqualTo(2);
    }

    @Test
    void aReusedKeyWithADifferentBodyIs422AndAStaleVersionWithoutAKeyIsStill409() throws Exception {
        User exec = executive("idem-2@example.test");
        String key = UUID.randomUUID().toString();
        mvc.perform(save(exec, key, profile(1, "Ada Synthetic"))).andExpect(status().isOk());

        mvc.perform(save(exec, key, profile(1, "Someone Else")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(save(exec, null, profile(1, "Stale Write")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(save(exec, "not-a-uuid", profile(2, "Ada Synthetic"))).andExpect(status().isBadRequest());
        assertThat(version(exec)).isEqualTo(2);
    }

    @Test
    void fiveParallelIdenticalRequestsApplyExactlyOnce() throws Exception {
        User exec = executive("idem-3@example.test");
        db.update("INSERT INTO executive_profiles(user_id) VALUES (?)", exec.getId());
        String key = UUID.randomUUID().toString();
        String body = profile(1, "Ada Synthetic");
        var pool = Executors.newFixedThreadPool(5);
        List<Future<String>> results = new ArrayList<>();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM executive_profiles WHERE user_id = ? FOR UPDATE")) {
                lock.setObject(1, exec.getId());
                lock.executeQuery();
            }
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> mvc.perform(save(exec, key, body)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString()));
            }
            Thread.sleep(1500);
            blocker.rollback();
        }
        List<String> bodies = new ArrayList<>();
        for (Future<String> f : results) bodies.add(f.get());
        pool.shutdown();

        assertThat(bodies).hasSize(5).allMatch(b -> b.equals(bodies.get(0)));
        assertThat(version(exec)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ?", Integer.class, exec.getId())).isEqualTo(1);
    }

    private User executive(String email) {
        User u = auth.create(email, "VaultIdemPass12", "EXECUTIVE");
        db.update("INSERT INTO consents(user_id) VALUES (?)", u.getId());
        return u;
    }

    private int version(User u) {
        Integer v = db.queryForObject("SELECT version FROM executive_profiles WHERE user_id = ?", Integer.class, u.getId());
        return v == null ? 0 : v;
    }

    private static String profile(int version, String name) {
        return "{\"version\":" + version + ",\"currentStep\":1,\"data\":{\"fullName\":\"" + name + "\"}}";
    }

    private static MockHttpServletRequestBuilder save(User user, String key, String body) {
        var principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        var token = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXECUTIVE")));
        MockHttpServletRequestBuilder request = put("/api/onboarding/steps/profile").with(authentication(token)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("Idempotency-Key", key);
    }
}
