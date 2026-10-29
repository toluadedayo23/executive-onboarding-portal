package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.notification.PushSender.Target;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PushSubscriptionRepository {
    private static final RowMapper<Target> TARGET = (rs, n) -> new Target(
            rs.getObject("id", UUID.class), rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth"));

    private final JdbcTemplate db;

    public PushSubscriptionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Owned> findByEndpointForUpdate(String endpoint) {
        return db.query("SELECT id, user_id FROM push_subscriptions WHERE endpoint = ? FOR UPDATE",
                (rs, n) -> new Owned(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), endpoint).stream().findFirst();
    }

    public int countForUser(UUID userId) {
        Integer count = db.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public UUID insert(UUID userId, String endpoint, String p256dh, String auth) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO push_subscriptions(id, user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?, ?)",
                id, userId, endpoint, p256dh, auth);
        return id;
    }

    public void update(UUID id, UUID userId, String p256dh, String auth) {
        db.update("UPDATE push_subscriptions SET user_id = ?, p256dh = ?, auth = ? WHERE id = ?", userId, p256dh, auth, id);
    }

    public Optional<UUID> deleteForUser(UUID userId, String endpoint) {
        return db.query("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ? RETURNING id",
                (rs, n) -> rs.getObject(1, UUID.class), userId, endpoint).stream().findFirst();
    }

    public List<Target> findForActiveUser(UUID userId) {
        return db.query("""
                SELECT s.id, s.endpoint, s.p256dh, s.auth FROM push_subscriptions s
                JOIN users u ON u.id = s.user_id
                WHERE s.user_id = ? AND u.active AND u.email NOT LIKE 'deleted+%'
                """, TARGET, userId);
    }

    public void deleteById(UUID id) {
        db.update("DELETE FROM push_subscriptions WHERE id = ?", id);
    }

    public int deleteAllForUser(UUID userId) {
        return db.update("DELETE FROM push_subscriptions WHERE user_id = ?", userId);
    }

    public record Owned(UUID id, UUID userId) {
    }
}
