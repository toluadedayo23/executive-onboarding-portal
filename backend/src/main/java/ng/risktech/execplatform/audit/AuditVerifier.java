package ng.risktech.execplatform.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditVerifier {
    private static final String GEN = "0".repeat(64);
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public AuditVerifier(JdbcTemplate d) {
        db = d;
    }

    @Transactional(readOnly = true)
    public Result verify() {
        long last = 0, count = 0;
        Long broken = null, head = null;
        String headHash = null, reason = null, prev = GEN;
        boolean valid = true;
        while (valid) {
            List<Row> rows = db.query("SELECT id,actor_id,actor_role,action,entity_type,entity_id,detail::text,host(ip_address),created_at,prev_hash,hash FROM audit_log WHERE id>? ORDER BY id LIMIT 1000", (rs, n) -> new Row(rs.getLong(1), (UUID) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getString(10), rs.getString(11)), last);
            if (rows.isEmpty()) break;
            for (Row r : rows) {
                count++;
                head = r.id;
                headHash = r.hash;
                try {
                    if (!r.prev.equals(prev)) {
                        valid = false;
                        broken = r.id;
                        reason = count == 1 ? "GENESIS_MISMATCH" : "PREV_HASH_MISMATCH";
                        break;
                    }
                    String d = r.detail;
                    if (d != null) {
                        Map<String, String> m = json.readValue(d, new TypeReference<>() {
                        });
                        d = AuditCanonicalizer.detail(m);
                    }
                    String expected = AuditCanonicalizer.hash(AuditCanonicalizer.canonical(r.actor, r.role, r.action, r.type, r.entity, d, r.ip, r.created, r.prev));
                    if (!expected.equals(r.hash)) {
                        valid = false;
                        broken = r.id;
                        reason = "HASH_MISMATCH";
                        break;
                    }
                    prev = r.hash;
                    last = r.id;
                } catch (Exception ex) {
                    valid = false;
                    broken = r.id;
                    reason = "MALFORMED";
                    break;
                }
            }
        }
        return new Result(valid, count, broken, reason, head, headHash, Instant.now());
    }

    public record Result(boolean valid, long entriesChecked, Long firstBrokenId, String reason, Long headId,
                         String headHash, Instant checkedAt) {
    }

    private record Row(long id, UUID actor, String role, String action, String type, String entity, String detail,
                       String ip, Instant created, String prev, String hash) {
    }
}
