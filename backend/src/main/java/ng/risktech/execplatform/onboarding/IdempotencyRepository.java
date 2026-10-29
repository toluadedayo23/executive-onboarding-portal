package ng.risktech.execplatform.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate db;

    public IdempotencyRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Stored> find(UUID userId, UUID key) {
        return db.query("SELECT request_hmac, response_status, response_body::text FROM idempotency_keys "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                (rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)), userId, key).stream().findFirst();
    }

    public void insert(UUID userId, UUID key, String requestHmac, int status, String body) {
        db.update("INSERT INTO idempotency_keys(user_id, idempotency_key, request_hmac, response_status, response_body) "
                + "VALUES (?, ?, ?, ?, CAST(? AS jsonb))", userId, key, requestHmac, status, body);
    }

    public int deleteOlderThan(OffsetDateTime cutoff) {
        return db.update("DELETE FROM idempotency_keys WHERE created_at < ?", cutoff);
    }

    public void deleteAllForUser(UUID userId) {
        db.update("DELETE FROM idempotency_keys WHERE user_id = ?", userId);
    }

    public record Stored(String requestHmac, int status, String body) {
    }
}
