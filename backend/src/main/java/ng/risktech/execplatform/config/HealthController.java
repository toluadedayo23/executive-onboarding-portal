package ng.risktech.execplatform.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    Map<String, String> health() {
        return Map.of("status", "UP", "service", "executive-platform", "time", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }
}
