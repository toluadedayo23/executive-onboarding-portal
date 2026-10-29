package ng.risktech.execplatform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService implements AuditRecorder {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String GEN = "0".repeat(64);
    private final JdbcTemplate db;
    private final AuditContext context;

    public AuditService(JdbcTemplate d, AuditContext c) {
        db = d;
        context = c;
    }

    static void validate(Map<String, String> d) {
        if (d == null) return;
        if (d.size() > 20) throw new IllegalArgumentException("Audit detail too large");
        for (var x : d.entrySet()) {
            if (x.getKey() == null || !x.getKey().matches("[A-Za-z0-9_]{1,50}") || x.getValue() == null || x.getValue().length() > 200 || x.getKey().toLowerCase(Locale.ROOT).matches(".*(password|token|secret|jwt|authorization|email|phone|national|encrypted).*"))
                throw new IllegalArgumentException("Invalid audit detail");
        }
    }

    @Override
    @Transactional
    public void record(AuditEvent e) {
        append(e);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStandalone(AuditEvent e) {
        try {
            append(e);
        } catch (Exception ex) {
            log.error("Standalone audit append failed action={} entityId={}", e.action(), e.entityId(), ex);
        }
    }

    private void append(AuditEvent e) {
        validate(e.detail());
        db.execute("SET LOCAL lock_timeout = '5s'");
        db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, 8742391L);
                statement.execute();
                return null;
            }
        });
        String prev = db.query("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : GEN);
        UUID actor = e.actorIdOverride() != null ? e.actorIdOverride() : context.actorId();
        String role = e.actorRoleOverride() != null ? e.actorRoleOverride() : context.actorRole();
        String ip = normalizeIp(context.ip());
        String detail = AuditCanonicalizer.detail(e.detail());
        Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String hash = AuditCanonicalizer.hash(AuditCanonicalizer.canonical(actor, role, e.action(), e.entityType(), e.entityId(), detail, ip, created, prev));
        db.update("INSERT INTO audit_log(actor_id,actor_role,action,entity_type,entity_id,detail,ip_address,created_at,prev_hash,hash) VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS inet),?,?,?)", actor, role, e.action(), e.entityType(), e.entityId(), detail, ip, java.sql.Timestamp.from(created), prev, hash);
    }

    private String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        return db.queryForObject("SELECT host(CAST(? AS inet))", String.class, ip);
    }
}
