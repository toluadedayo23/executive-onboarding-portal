package ng.risktech.execplatform.consent;

import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ConsentService {
    public static final String NOTICE = "We collect the information needed to assess your executive experience and "
            + "qualifications. Your data is retained for the prototype review period and may be retained where "
            + "needed for audit or legal obligations. This notice and retention assumption require legal review "
            + "before production use.";

    private final JdbcTemplate db;
    private final AuditRecorder audit;

    public ConsentService(JdbcTemplate db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
    }

    public Status status(UUID userId) {
        return new Status(hasValidConsent(userId), NOTICE);
    }

    public boolean hasValidConsent(UUID userId) {
        Integer count = db.queryForObject(
                "SELECT count(*) FROM consents WHERE user_id = ? AND withdrawn_at IS NULL", Integer.class, userId);
        return count != null && count > 0;
    }

    public void requireConsent(UUID userId) {
        if (!hasValidConsent(userId)) {
            throw ApiException.forbidden("CONSENT_REQUIRED", "Consent is required before personal data can be saved");
        }
    }

    @Transactional
    public void grant(AuthenticatedUser actor, String ip) {
        if (hasValidConsent(actor.id())) return;
        db.update("INSERT INTO consents(user_id, ip_address) VALUES (?, CAST(? AS inet))", actor.id(), ip);
        audit.record(AuditEvent.of(AuditAction.CONSENT_GRANTED, AuditEntityType.CONSENT, actor.id().toString(), null));
    }

    public record Status(boolean granted, String notice) {
    }
}
