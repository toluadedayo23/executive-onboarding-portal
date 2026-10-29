package ng.risktech.execplatform.notification;

public interface PushSender {
    Result send(Target target, byte[] payload);

    enum Result {
        OK,
        GONE,
        FAILED
    }

    record Target(java.util.UUID id, String endpoint, String p256dh, String auth) {
    }
}
