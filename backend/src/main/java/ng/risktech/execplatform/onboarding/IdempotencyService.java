package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveRequest;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository repository;
    private final ObjectMapper json;
    private final SecretKeySpec hmacKey;
    private final long ttlHours;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper json,
                              @Value("${app.encryption-key}") String encryptionKey,
                              @Value("${app.idempotency-ttl-hours:168}") long ttlHours) {
        this.repository = repository;
        this.json = json;
        this.ttlHours = ttlHours;
        // Derived subkey: the encryption key itself is only used for encryption
        this.hmacKey = new SecretKeySpec(hmac(Base64.getDecoder().decode(encryptionKey), "idempotency-v1"), "HmacSHA256");
    }

    public static UUID parseKey(String header) {
        if (header == null) return null;
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Idempotency-Key must be a UUID");
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node == null) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) out.set(name, sorted(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(sorted(item)));
            return out;
        }
        return node;
    }

    private static byte[] hmac(byte[] key, String message) {
        return hmac(new SecretKeySpec(key, "HmacSHA256"), message);
    }

    private static byte[] hmac(SecretKeySpec key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    public Outcome run(UUID userId, UUID key, String requestHmac, Supplier<SaveResponse> save) {
        if (key == null) return new Outcome(save.get(), false);
        Optional<IdempotencyRepository.Stored> stored = repository.find(userId, key);
        if (stored.isPresent()) {
            if (!stored.get().requestHmac().equals(requestHmac)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This request key was already used for a different change");
            }
            return new Outcome(read(stored.get().body()), true);
        }
        SaveResponse response = save.get();
        repository.insert(userId, key, requestHmac, 200, write(response));
        return new Outcome(response, false);
    }

    public String requestHmac(String step, SaveRequest request) {
        ObjectNode canonical = JsonNodeFactory.instance.objectNode();
        canonical.put("step", step);
        canonical.put("version", request.version());
        if (request.currentStep() == null) canonical.putNull("currentStep");
        else canonical.put("currentStep", request.currentStep());
        canonical.set("data", sorted(request.data()));
        return HexFormat.of().formatHex(hmac(hmacKey, sorted(canonical).toString()));
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void purgeExpired() {
        int removed = repository.deleteOlderThan(OffsetDateTime.now().minusHours(ttlHours));
        if (removed > 0) log.info("Purged {} expired idempotency keys", removed);
    }

    private SaveResponse read(String body) {
        try {
            SaveResponse stored = json.readValue(body, SaveResponse.class);
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String step : DraftShape.STEPS) {
                if (stored.stepStatus().containsKey(step)) ordered.put(step, stored.stepStatus().get(step));
            }
            return new SaveResponse(stored.version(), stored.currentStep(), ordered);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response is unreadable");
        }
    }

    private String write(SaveResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Response could not be stored");
        }
    }

    public record Outcome(SaveResponse body, boolean replayed) {
    }
}
