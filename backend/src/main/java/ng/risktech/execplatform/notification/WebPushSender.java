package ng.risktech.execplatform.notification;

import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class WebPushSender implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final int TTL_SECONDS = 24 * 60 * 60;
    private static final long TIMEOUT_SECONDS = 10;

    private final PushService service;

    public WebPushSender(PushProperties props) {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
            Security.addProvider(new BouncyCastleProvider());
        try {
            service = new PushService(props.vapidPublicKey(), props.vapidPrivateKey(), props.vapidSubject());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid VAPID key pair");
        }
    }

    @Override
    public Result send(Target target, byte[] payload) {
        Future<HttpResponse> pending = null;
        try {
            Notification notification = Notification.builder()
                    .endpoint(target.endpoint())
                    .userPublicKey(target.p256dh())
                    .userAuth(target.auth())
                    .payload(payload)
                    .ttl(TTL_SECONDS)
                    .urgency(Urgency.NORMAL)
                    .build();
            pending = service.sendAsync(notification, Encoding.AES128GCM);
            int status = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) return Result.OK;
            if (status == 404 || status == 410) return Result.GONE;
            log.warn("Push service rejected a notification subscriptionId={} status={}", target.id(), status);
            return Result.FAILED;
        } catch (Exception e) {
            if (pending != null) pending.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Push send failed subscriptionId={} error={}", target.id(), e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }
}
