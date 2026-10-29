package ng.risktech.execplatform.notification;

public interface PushSender {
    enum Result {
        OK,
        GONE,
        FAILED
    }

    record Target(java.util.UUID id, String endpoint, String p256dh, String auth) {
    }

    Result send(Target target, byte[] payload);
}
