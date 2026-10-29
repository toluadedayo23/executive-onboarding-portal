package ng.risktech.execplatform.audit;

import java.util.Map;
import java.util.UUID;

public record AuditEvent(String action, String entityType, String entityId, Map<String, String> detail,
                         UUID actorIdOverride, String actorRoleOverride) {

    public static AuditEvent of(String action, String entityType, String entityId, Map<String, String> detail) {
        return new AuditEvent(action, entityType, entityId, detail, null, null);
    }
}
