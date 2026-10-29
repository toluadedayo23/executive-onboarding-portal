package ng.risktech.execplatform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditAdminController {
    private final AuditVerifier verifier;
    private final JdbcTemplate db;

    public AuditAdminController(AuditVerifier verifier, JdbcTemplate db) {
        this.verifier = verifier;
        this.db = db;
    }

    private static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @GetMapping("/verify")
    public AuditVerifier.Result verify() {
        return verifier.verify();
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "KEY") Category category,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) String user,
                                    @RequestParam(required = false) LocalDate from,
                                    @RequestParam(required = false) LocalDate to) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        if (user != null && user.length() > 254) throw new IllegalArgumentException("User filter too long");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("From is after to");
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.created_at, a.actor_id, actor.email AS actor_email, a.actor_role, a.action,
                       a.entity_type, a.entity_id, a.detail::text AS detail, host(a.ip_address) AS ip, a.hash,
                       COALESCE(target_user.email,
                                case_profile.draft_data->'profile'->>'fullName',
                                profile.draft_data->'profile'->>'fullName') AS target
                FROM audit_log a
                LEFT JOIN users actor ON actor.id = a.actor_id
                LEFT JOIN users target_user ON a.entity_type IN ('USER', 'CONSENT') AND target_user.id::text = a.entity_id
                LEFT JOIN vetting_cases vc ON a.entity_type = 'VETTING_CASE' AND vc.id::text = a.entity_id
                LEFT JOIN executive_profiles case_profile ON case_profile.id = vc.profile_id
                LEFT JOIN executive_profiles profile ON a.entity_type = 'EXECUTIVE_PROFILE' AND profile.id::text = a.entity_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        Set<String> actions = switch (category) {
            case KEY -> AuditAction.KEY_EVENTS;
            case SECURITY -> AuditAction.SECURITY_EVENTS;
            case ALL -> null;
        };
        if (actions != null) {
            sql.append(" AND a.action = ANY (?)");
            params.add(actions.toArray(String[]::new));
        }
        if (user != null && !user.isBlank()) {
            sql.append(" AND (actor.email ILIKE ? OR target_user.email ILIKE ?)");
            String pattern = "%" + likeEscape(user.trim()) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (from != null) {
            sql.append(" AND a.created_at >= ?");
            params.add(from.atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (to != null) {
            sql.append(" AND a.created_at < ?");
            params.add(to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND a.action = ?");
            params.add(action);
        }
        sql.append(" ORDER BY a.id DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);

        List<Entry> rows = db.query(sql.toString(), (rs, n) -> new Entry(rs.getLong("id"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_email"), rs.getString("actor_role"), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("target"),
                rs.getString("detail"), rs.getString("ip"), rs.getString("hash")), params.toArray());
        return Map.of("content", rows, "page", page, "size", size);
    }

    public enum Category {KEY, SECURITY, ALL}

    public record Entry(long id, OffsetDateTime createdAt, UUID actorId, String actorEmail, String actorRole,
                        String action, String entityType, String entityId, String target, String detail, String ip,
                        String hash) {
    }
}
