package ng.risktech.execplatform.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
public class SecurityGuards {
    private final String rawSecret;
    private final boolean prod;
    private final boolean seed;
    private final String encryptionKey;

    public SecurityGuards(@Value("${JWT_SECRET:}") String raw, @Value("${spring.profiles.active:dev}") String p,
                          @Value("${app.seed-enabled:false}") boolean seed, @Value("${app.encryption-key:}") String encryptionKey) {
        rawSecret = raw;
        prod = "prod".equals(p);
        this.seed = seed;
        this.encryptionKey = encryptionKey;
    }

    @PostConstruct
    void validate() {
        if (!prod) return;
        if (rawSecret == null || rawSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes in prod");
        }
        if (seed) throw new IllegalStateException("Seeding is disabled in prod");
        try {
            if (Base64.getDecoder().decode(encryptionKey).length != 32) throw new IllegalArgumentException();
        } catch (Exception ex) {
            throw new IllegalStateException("ENCRYPTION_KEY must encode exactly 32 bytes in prod");
        }
    }
}
