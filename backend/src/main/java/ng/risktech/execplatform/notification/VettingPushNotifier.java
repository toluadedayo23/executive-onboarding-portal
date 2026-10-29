package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.notification.PushSender.Result;
import ng.risktech.execplatform.notification.PushSender.Target;
import ng.risktech.execplatform.vetting.VettingStatus;
import ng.risktech.execplatform.vetting.VettingStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

@Component
public class VettingPushNotifier {
    static final Set<VettingStatus> NOTIFY_ON = EnumSet.of(
            VettingStatus.MORE_INFO_REQUIRED, VettingStatus.VERIFIED, VettingStatus.REJECTED);
    static final String PAYLOAD_JSON =
            "{\"title\":\"RiskTech & Advisory\",\"body\":\"Your vetting status has been updated\",\"url\":\"/\",\"tag\":\"vetting-status\"}";
    private static final Logger log = LoggerFactory.getLogger(VettingPushNotifier.class);

    private final PushSubscriptionRepository subscriptions;
    private final PushSender sender;
    private final PushConfig.PushStatus status;

    public VettingPushNotifier(PushSubscriptionRepository subscriptions, PushSender sender, PushConfig.PushStatus status) {
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.status = status;
    }

    @Async("pushExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(VettingStatusChangedEvent event) {
        if (!status.enabled() || !NOTIFY_ON.contains(event.newStatus())) return;
        for (Target target : subscriptions.findForActiveUser(event.executiveUserId())) {
            Result result;
            try {
                result = sender.send(target, PAYLOAD_JSON.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                result = Result.FAILED;
            }
            if (result == Result.GONE) {
                subscriptions.deleteById(target.id());
                log.info("Removed expired push subscription subscriptionId={}", target.id());
            } else if (result == Result.FAILED) {
                log.error("Push notification not delivered subscriptionId={}", target.id());
            }
        }
    }
}
