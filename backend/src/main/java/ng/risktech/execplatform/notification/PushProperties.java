package ng.risktech.execplatform.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.push")
public record PushProperties(String vapidPublicKey, String vapidPrivateKey, String vapidSubject, List<String> allowedHosts) {
}
