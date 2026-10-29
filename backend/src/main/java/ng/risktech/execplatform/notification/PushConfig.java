package ng.risktech.execplatform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Base64;

@Configuration
@EnableAsync
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {
    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    public record PushStatus(boolean enabled, String publicKey) {
    }

    @Bean
    PushStatus pushStatus(PushProperties props, Environment env) {
        String problem = problemWith(props);
        if (problem == null) return new PushStatus(true, props.vapidPublicKey());
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Push notifications are misconfigured: " + problem);
        }
        log.warn("Push notifications are disabled: {}", problem);
        return new PushStatus(false, null);
    }

    @Bean
    PushSender pushSender(PushStatus status, PushProperties props) {
        if (!status.enabled()) return (target, payload) -> PushSender.Result.FAILED;
        return new WebPushSender(props);
    }

    @Bean(name = "pushExecutor")
    ThreadPoolTaskExecutor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("push-");
        executor.setRejectedExecutionHandler((task, pool) -> log.error("Push queue full; a notification was dropped"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }

    static String problemWith(PushProperties p) {
        if (isBlank(p.vapidPublicKey()) || isBlank(p.vapidPrivateKey()) || isBlank(p.vapidSubject())) {
            return "VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT must all be set";
        }
        byte[] pub = decode(p.vapidPublicKey());
        if (pub == null || pub.length != 65 || pub[0] != 0x04) return "VAPID_PUBLIC_KEY must be a base64url uncompressed P-256 point";
        byte[] priv = decode(p.vapidPrivateKey());
        if (priv == null || priv.length != 32) return "VAPID_PRIVATE_KEY must be a base64url 32-byte P-256 private key";
        if (!p.vapidSubject().startsWith("mailto:") && !p.vapidSubject().startsWith("https://")) {
            return "VAPID_SUBJECT must be a mailto: or https:// URI";
        }
        return null;
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
