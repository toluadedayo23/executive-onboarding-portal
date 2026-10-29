package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A masked value coming back from the client means "keep the stored ciphertext".
 */
@Component
public class DraftProtector {
    static final String MASK = "••••";
    static final String ON_FILE = "On file";
    private static final String NATIONAL_ID = "profile.nationalId";
    private static final String REFEREE_EMAIL = "referee.email";
    private static final String REFEREE_PHONE = "referee.phone";

    private final FieldEncryptionService crypto;

    public DraftProtector(FieldEncryptionService crypto) {
        this.crypto = crypto;
    }

    static boolean isMasked(String value) {
        return value.startsWith(MASK) || value.equals(ON_FILE);
    }

    static String maskTail(String value) {
        return value.length() < 4 ? ON_FILE : MASK + value.substring(value.length() - 4);
    }

    static String maskEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 ? MASK + value.substring(at) : ON_FILE;
    }

    private static void copyOrRemove(ObjectNode target, String field, JsonNode storedCiphertext) {
        if (storedCiphertext != null && storedCiphertext.isTextual()) target.set(field, storedCiphertext);
        else target.remove(field);
    }

    public ObjectNode protectStep(String step, JsonNode incoming, JsonNode storedDraft) {
        ObjectNode data = incoming.deepCopy();
        if (step.equals("profile")) {
            protectNationalId(data, storedDraft.path("profile"));
        } else if (step.equals("referees")) {
            protectReferees(data, storedDraft.path("referees").path("items"));
        }
        return data;
    }

    public ObjectNode decrypt(JsonNode stored) {
        ObjectNode draft = stored.deepCopy();
        if (draft.path("profile") instanceof ObjectNode profile) {
            decryptField(profile, "nationalId", NATIONAL_ID);
        }
        for (JsonNode item : draft.path("referees").path("items")) {
            if (item instanceof ObjectNode referee) {
                decryptField(referee, "email", REFEREE_EMAIL);
                decryptField(referee, "phone", REFEREE_PHONE);
            }
        }
        return draft;
    }

    public ObjectNode maskForOwner(JsonNode stored) {
        ObjectNode draft = decrypt(stored);
        if (draft.path("profile") instanceof ObjectNode profile && profile.hasNonNull("nationalId")) {
            profile.put("nationalId", maskTail(profile.get("nationalId").asText()));
        }
        for (JsonNode item : draft.path("referees").path("items")) {
            if (item instanceof ObjectNode referee) {
                if (referee.hasNonNull("email")) referee.put("email", maskEmail(referee.get("email").asText()));
                if (referee.hasNonNull("phone")) referee.put("phone", maskTail(referee.get("phone").asText()));
            }
        }
        return draft;
    }

    public ObjectNode reviewerView(JsonNode stored) {
        ObjectNode draft = decrypt(stored);
        if (draft.path("profile") instanceof ObjectNode profile && profile.hasNonNull("nationalId")) {
            profile.put("nationalId", maskTail(profile.get("nationalId").asText()));
        }
        return draft;
    }

    public String encryptNationalId(String value) {
        return crypto.encrypt(value, NATIONAL_ID);
    }

    public String encryptRefereeEmail(String value) {
        return crypto.encrypt(value, REFEREE_EMAIL);
    }

    public String encryptRefereePhone(String value) {
        return crypto.encrypt(value, REFEREE_PHONE);
    }

    private void protectNationalId(ObjectNode profile, JsonNode storedProfile) {
        JsonNode value = profile.get("nationalId");
        if (value == null || value.isNull() || value.asText().isBlank()) {
            profile.remove("nationalId");
        } else if (isMasked(value.asText())) {
            copyOrRemove(profile, "nationalId", storedProfile.get("nationalId"));
        } else {
            profile.put("nationalId", crypto.encrypt(value.asText().trim(), NATIONAL_ID));
        }
    }

    private void protectReferees(ObjectNode data, JsonNode storedItems) {
        Map<String, JsonNode> storedById = new HashMap<>();
        for (JsonNode item : storedItems) {
            if (item.hasNonNull("id")) storedById.put(item.get("id").asText(), item);
        }
        if (!(data.get("items") instanceof ArrayNode items)) return;
        for (JsonNode item : items) {
            if (!(item instanceof ObjectNode referee)) continue;
            if (!referee.hasNonNull("id")) referee.put("id", UUID.randomUUID().toString());
            JsonNode stored = storedById.get(referee.get("id").asText());
            protectContact(referee, "email", REFEREE_EMAIL, stored);
            protectContact(referee, "phone", REFEREE_PHONE, stored);
        }
    }

    private void protectContact(ObjectNode referee, String field, String context, JsonNode stored) {
        JsonNode value = referee.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            referee.remove(field);
        } else if (isMasked(value.asText())) {
            copyOrRemove(referee, field, stored == null ? null : stored.get(field));
        } else {
            referee.put(field, crypto.encrypt(value.asText().trim(), context));
        }
    }

    private void decryptField(ObjectNode node, String field, String context) {
        if (node.hasNonNull(field)) node.put(field, crypto.decrypt(node.get(field).asText(), context));
    }
}
