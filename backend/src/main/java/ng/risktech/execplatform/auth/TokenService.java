package ng.risktech.execplatform.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Configuration
public class TokenService {
    public static String issue(JwtEncoder encoder, UserView u, String issuer, long ttl) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(u.id().toString()).issuer(issuer).issuedAt(now).expiresAt(now.plusSeconds(ttl)).id(UUID.randomUUID().toString()).claim("tv", u.tokenVersion()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.bcrypt-strength:12}") int strength) {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(strength);
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${app.jwt-secret}") String secret) {
        var key = new OctetSequenceKey.Builder(secret.getBytes(StandardCharsets.UTF_8))
                .algorithm(JWSAlgorithm.HS256)
                .keyID("exec-platform-hs256")
                .build();
        return new NimbusJwtEncoder((selector, context) -> java.util.List.of(key));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt-secret}") String secret, @Value("${app.jwt-issuer}") String issuer) {
        var d = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return d;
    }

    public record UserView(UUID id, int tokenVersion) {
    }
}
