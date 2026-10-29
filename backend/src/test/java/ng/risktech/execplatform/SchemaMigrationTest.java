package ng.risktech.execplatform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class SchemaMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired
    JdbcTemplate db;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
    }

    @Test
    void allTablesExist() {
        var names = db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class);
        assertThat(names).contains("users", "executive_profiles", "experiences", "sector_expertise", "qualifications", "referees", "documents", "vetting_cases", "vetting_notes", "consents", "audit_log", "push_subscriptions", "idempotency_keys");
    }

    @Test
    void requiredIndexesAndAuditTriggersExist() {
        var indexes = db.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname='public'", String.class);
        assertThat(indexes).contains("uq_users_email_lower", "idx_vetting_cases_status_submitted", "idx_vetting_cases_consultant_status", "idx_audit_log_entity", "idx_audit_log_actor_created", "idx_documents_profile_id");
        var triggers = db.queryForList("SELECT tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relname='audit_log' AND NOT tgisinternal", String.class);
        assertThat(triggers).contains("trg_audit_log_no_update", "trg_audit_log_no_delete", "trg_audit_log_no_truncate");
    }

    @Test
    void keyTypesAndForeignKeysAreCorrect() {
        assertThat(db.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='audit_log' AND column_name='id'", String.class)).isEqualTo("bigint");
        assertThat(db.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='users' AND column_name='id'", String.class)).isEqualTo("uuid");
        assertThat(db.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'", String.class)).contains("0");
        var deletes = db.queryForList("SELECT tc.table_name, rc.delete_rule FROM information_schema.referential_constraints rc JOIN information_schema.table_constraints tc ON tc.constraint_name=rc.constraint_name WHERE tc.table_schema='public'");
        assertThat(deletes).isNotEmpty();
    }

    @Test
    void databaseRejectsInvalidValues() {
        assertThatThrownBy(() -> db.update("INSERT INTO users(email,password_hash,role) VALUES ('Case@Test','x','NOPE')")).isInstanceOf(Exception.class);
        var user = UUID.randomUUID();
        db.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,?,'EXECUTIVE')", user, "case-" + user + "@example.test", "x");
        assertThatThrownBy(() -> db.update("INSERT INTO users(email,password_hash,role) VALUES (?,?,?)", "CASE-" + user + "@EXAMPLE.TEST", "x", "EXECUTIVE")).isInstanceOf(Exception.class);
        var profile = UUID.randomUUID();
        db.update("INSERT INTO executive_profiles(id,user_id) VALUES (?,?)", profile, user);
        assertThatThrownBy(() -> db.update("INSERT INTO executive_profiles(user_id) VALUES (?)", user)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO executive_profiles(user_id,version) VALUES (?,0)", user)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO vetting_cases(profile_id,status) VALUES (?, 'INVALID')", profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO vetting_cases(profile_id,status) VALUES (?, 'REJECTED')", profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO vetting_cases(profile_id,status) VALUES (?, 'MORE_INFO_REQUIRED')", UUID.randomUUID())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO experiences(profile_id,type,organisation,title,start_date,end_date,description) VALUES (?, 'EXECUTIVE','o','t','2025-01-02','2025-01-01','d')", profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO documents(profile_id,type,original_name,stored_name,content_type,size_bytes,sha256) VALUES (?, 'OTHER','x','y','x',1,repeat('a',64))", profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO documents(profile_id,type,original_name,stored_name,content_type,size_bytes,sha256) VALUES (?, 'CV','x','z','x',0,repeat('a',64))", profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO documents(profile_id,type,original_name,stored_name,content_type,size_bytes,sha256) VALUES (?, 'CV','x','q','x',1,'bad')", profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO sector_expertise(profile_id,sector,years_experience) VALUES (?, 'Tech',1), (?, 'Tech',2)", profile, profile)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("INSERT INTO consents(user_id,granted_at,withdrawn_at) VALUES (?, now(), now()-interval '1 day')", user)).isInstanceOf(Exception.class);
    }

    @Test
    void updatedAtIsMaintainedInAnotherTransaction() throws Exception {
        var user = UUID.randomUUID();
        db.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,'x','EXECUTIVE')", user, "updated-" + user + "@example.test");
        var before = db.queryForObject("SELECT updated_at FROM users WHERE id=?", java.time.OffsetDateTime.class, user);
        Thread.sleep(5);
        db.update("UPDATE users SET active=false WHERE id=?", user);
        var after = db.queryForObject("SELECT updated_at FROM users WHERE id=?", java.time.OffsetDateTime.class, user);
        assertThat(after).isAfter(before);
    }

    @Test
    void auditLogIsAppendOnly() {
        var hash = UUID.randomUUID().toString().replace("-", "").repeat(4).substring(0, 64);
        var prev = "a".repeat(64);
        db.update("INSERT INTO audit_log(actor_role,action,prev_hash,hash) VALUES ('ADMIN','schema-test',?,?)", prev, hash);
        assertThatThrownBy(() -> db.update("UPDATE audit_log SET action='changed' WHERE hash=?", hash)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("DELETE FROM audit_log WHERE hash=?", hash)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.execute("TRUNCATE audit_log")).isInstanceOf(Exception.class);
        assertThat(db.queryForObject("SELECT action FROM audit_log WHERE hash=?", String.class, hash)).isEqualTo("schema-test");
    }
}
