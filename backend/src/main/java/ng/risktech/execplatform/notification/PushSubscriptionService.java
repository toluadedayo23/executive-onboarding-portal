package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.notification.PushConfig.PushStatus;
import ng.risktech.execplatform.notification.PushDtos.SubscribeRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PushSubscriptionService {
    static final int MAX_PER_USER = 5;

    private final PushSubscriptionRepository subscriptions;
    private final PushProperties props;
    private final PushStatus status;
    private final AuditRecorder audit;
    private final JdbcTemplate db;

    public PushSubscriptionService(PushSubscriptionRepository subscriptions, PushProperties props, PushStatus status,
                                   AuditRecorder audit, JdbcTemplate db) {
        this.subscriptions = subscriptions;
        this.props = props;
        this.status = status;
        this.audit = audit;
        this.db = db;
    }

    public record Result(UUID id, boolean created) {
    }

    public String publicKey() {
        requireEnabled();
        return status.publicKey();
    }

    @Transactional
    public Result subscribe(AuthenticatedUser user, SubscribeRequest request) {
        requireEnabled();
        SubscriptionValidator.validate(request.endpoint(), request.keys().p256dh(), request.keys().auth(), props.allowedHosts());
        db.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user.id());

        Optional<PushSubscriptionRepository.Owned> existing = subscriptions.findByEndpointForUpdate(request.endpoint());
        if (existing.isPresent() && existing.get().userId().equals(user.id())) {
            subscriptions.update(existing.get().id(), user.id(), request.keys().p256dh(), request.keys().auth());
            return new Result(existing.get().id(), false);
        }
        if (subscriptions.countForUser(user.id()) >= MAX_PER_USER) {
            throw ApiException.conflict("LIMIT_REACHED", "You can enable notifications on up to 5 browsers. Turn one off first.");
        }
        UUID id;
        String change;
        if (existing.isPresent()) {
            id = existing.get().id();
            subscriptions.update(id, user.id(), request.keys().p256dh(), request.keys().auth());
            change = "reassigned";
        } else {
            id = subscriptions.insert(user.id(), request.endpoint(), request.keys().p256dh(), request.keys().auth());
            change = "created";
        }
        audit.record(AuditEvent.of(AuditAction.PUSH_SUBSCRIBED, AuditEntityType.USER, user.id().toString(),
                Map.of("subscriptionId", id.toString(), "change", change)));
        return new Result(id, true);
    }

    @Transactional
    public void unsubscribe(AuthenticatedUser user, String endpoint) {
        requireEnabled();
        subscriptions.deleteForUser(user.id(), endpoint).ifPresent(id ->
                audit.record(AuditEvent.of(AuditAction.PUSH_UNSUBSCRIBED, AuditEntityType.USER, user.id().toString(),
                        Map.of("subscriptionId", id.toString()))));
    }

    private void requireEnabled() {
        if (!status.enabled()) throw ApiException.unavailable("PUSH_DISABLED", "Notifications are not available on this server");
    }
}
