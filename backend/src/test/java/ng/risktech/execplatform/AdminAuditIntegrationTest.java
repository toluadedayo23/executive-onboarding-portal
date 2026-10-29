package ng.risktech.execplatform;

import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditAdminController;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.audit.AuditAdminController.Category;
import ng.risktech.execplatform.auth.AuthService;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Transactional
class AdminAuditIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
    }

    @Autowired AuthService auth;
    @Autowired AuditAdminController auditController;
    @Autowired JdbcTemplate db;
    @Autowired AuditRecorder audit;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deactivationIsAttributedToTheAdminWithTheirRole() {
        User admin = auth.create("admin9@example.test", "VaultAdminNine9", "ADMIN");
        User consultant = auth.create("consultant9@example.test", "VaultConsultNine9", "CONSULTANT");
        signIn(admin);

        auth.setActive(consultant.getId(), false, admin.getId());

        Map<String, Object> row = db.queryForMap("SELECT actor_id, actor_role, detail->>'targetRole' AS target_role "
                + "FROM audit_log WHERE action = 'USER_DEACTIVATED' AND entity_id = ?", consultant.getId().toString());
        assertThat(row.get("actor_id")).isEqualTo(admin.getId());
        assertThat(row.get("actor_role")).isEqualTo("ADMIN");
        assertThat(row.get("target_role")).isEqualTo("CONSULTANT");
    }

    @Test
    void keyEventsViewHidesSecurityNoiseAndNamesTheTarget() {
        User admin = auth.create("admin8@example.test", "VaultAdminEight8", "ADMIN");
        User consultant = auth.create("consultant8@example.test", "VaultConsultEight8", "CONSULTANT");
        signIn(admin);
        auth.setActive(consultant.getId(), false, admin.getId());
        audit.record(new AuditEvent(AuditAction.LOGIN_FAILURE, AuditEntityType.USER, consultant.getId().toString(), null, null, null));

        @SuppressWarnings("unchecked")
        var key = (List<AuditAdminController.Entry>) auditController.list(0, 25, Category.KEY, null, null, null, null).get("content");
        assertThat(key).extracting("action").contains("USER_DEACTIVATED").doesNotContain("LOGIN_FAILURE");
        @SuppressWarnings("unchecked")
        var security = (List<AuditAdminController.Entry>) auditController.list(0, 25, Category.SECURITY, null, null, null, null).get("content");
        assertThat(security).extracting("action").containsExactly("LOGIN_FAILURE");
        assertThat(key).filteredOn(e -> e.action().equals("USER_DEACTIVATED"))
                .extracting("target").containsExactly("consultant8@example.test");
    }

    @Test
    void filtersByInvolvedUserAndDateRange() {
        User admin = auth.create("admin7@example.test", "VaultAdminSeven7", "ADMIN");
        User target = auth.create("filter.target7@example.test", "VaultTargetSeven7", "CONSULTANT");
        signIn(admin);
        auth.setActive(target.getId(), false, admin.getId());
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        @SuppressWarnings("unchecked")
        var byTarget = (List<AuditAdminController.Entry>) auditController.list(0, 25, Category.ALL, null, "FILTER.TARGET7", today, today).get("content");
        assertThat(byTarget).extracting("action").contains("USER_DEACTIVATED");
        assertThat(byTarget).allMatch(e -> "filter.target7@example.test".equals(e.target()) || "filter.target7@example.test".equals(e.actorEmail()));

        @SuppressWarnings("unchecked")
        var byActor = (List<AuditAdminController.Entry>) auditController.list(0, 25, Category.ALL, null, "admin7@", null, null).get("content");
        assertThat(byActor).extracting("actorEmail").containsOnly("admin7@example.test");

        @SuppressWarnings("unchecked")
        var yesterday = (List<AuditAdminController.Entry>) auditController.list(0, 25, Category.ALL, null, "filter.target7", today.minusDays(1), today.minusDays(1)).get("content");
        assertThat(yesterday).isEmpty();

        @SuppressWarnings("unchecked")
        var wildcard = (List<AuditAdminController.Entry>) auditController.list(0, 25, Category.ALL, null, "%", null, null).get("content");
        assertThat(wildcard).isEmpty();
    }

    private static void signIn(User user) {
        var principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))));
    }
}
