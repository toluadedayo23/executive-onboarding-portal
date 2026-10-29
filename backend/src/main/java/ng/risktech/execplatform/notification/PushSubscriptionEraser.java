package ng.risktech.execplatform.notification;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PushSubscriptionEraser {
    private final PushSubscriptionRepository subscriptions;

    public PushSubscriptionEraser(PushSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    public void eraseFor(UUID userId) {
        subscriptions.deleteAllForUser(userId);
    }
}
