package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionValidatorTest {
    static final List<String> HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "*.notify.windows.com", "web.push.apple.com");
    static final String FCM = "https://fcm.googleapis.com/fcm/send/synthetic-token:APA91bSynthetic";
    static final String P256DH = b64(key65());
    static final String AUTH = b64(new byte[16]);

    private static void rejects(String endpoint, String p256dh, String auth) {
        assertThatThrownBy(() -> SubscriptionValidator.validate(endpoint, p256dh, auth, HOSTS))
                .isInstanceOf(ApiException.class).hasMessage("This push subscription is not supported");
    }

    private static byte[] key65() {
        byte[] key = new byte[65];
        key[0] = 0x04;
        return key;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void acceptsAValidFcmStyleSubscription() {
        assertThatCode(() -> SubscriptionValidator.validate(FCM, P256DH, AUTH, HOSTS)).doesNotThrowAnyException();
        assertThatCode(() -> SubscriptionValidator.validate("https://wns2-par02p.notify.windows.com/w/?token=x", P256DH, AUTH, HOSTS))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnythingThatCouldTurnIntoSsrfOrIsMalformed() {
        rejects("http://fcm.googleapis.com/fcm/send/x", P256DH, AUTH);
        rejects("https://evil.example.com/fcm/send/x", P256DH, AUTH);
        rejects("https://fcm.googleapis.com.evil.example/x", P256DH, AUTH);
        rejects("https://evilnotify.windows.com/x", P256DH, AUTH);
        rejects("https://fcm.googleapis.com:8443/x", P256DH, AUTH);
        rejects("https://user:pw@fcm.googleapis.com/x", P256DH, AUTH);
        rejects(FCM + "/" + "a".repeat(500), P256DH, AUTH);
        rejects(FCM, b64(new byte[64]), AUTH);
        rejects(FCM, "not base64!!", AUTH);
        rejects(FCM, P256DH, b64(new byte[8]));
    }
}
