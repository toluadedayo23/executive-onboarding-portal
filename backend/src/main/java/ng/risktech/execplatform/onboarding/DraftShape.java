package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ng.risktech.execplatform.common.ApiException;

import java.util.List;
import java.util.Map;

final class DraftShape {
    static final List<String> STEPS = List.of("profile", "experience", "sectors", "qualifications", "referees");

    private static final Map<String, Map<String, Integer>> FIELDS = Map.of(
            "profile", Map.of("fullName", 120, "headline", 120, "summary", 2000, "phone", 20,
                    "country", 80, "city", 80, "nationalId", 32),
            "experience", Map.of("type", 20, "organisation", 120, "title", 120, "startDate", 10,
                    "endDate", 10, "description", 2000),
            "sectors", Map.of("sector", 80, "yearsExperience", 3),
            "qualifications", Map.of("title", 120, "institution", 120, "yearObtained", 4),
            "referees", Map.of("id", 36, "name", 120, "relationship", 80, "email", 254, "phone", 20));

    private static final Map<String, Integer> MAX_ITEMS = Map.of(
            "experience", 20, "sectors", 10, "qualifications", 15, "referees", 5);

    private DraftShape() {
    }

    static boolean isStep(String step) {
        return STEPS.contains(step);
    }

    static ObjectNode clean(String step, JsonNode data) {
        if (!data.isObject()) throw ApiException.badRequest("Step data must be an object");
        if (step.equals("profile")) return cleanObject(data, FIELDS.get("profile"));

        JsonNode items = data.get("items");
        if (items == null || !items.isArray()) throw ApiException.badRequest("Step data must contain an items list");
        if (items.size() > MAX_ITEMS.get(step)) throw ApiException.badRequest("Too many items");
        ArrayNode cleaned = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : items) {
            if (!item.isObject()) throw ApiException.badRequest("Each item must be an object");
            cleaned.add(cleanObject(item, FIELDS.get(step)));
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("items", cleaned);
        return result;
    }

    private static ObjectNode cleanObject(JsonNode source, Map<String, Integer> allowed) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        allowed.forEach((field, maxLength) -> {
            JsonNode value = source.get(field);
            if (value == null || value.isNull()) return;
            if (!value.isTextual() && !value.isNumber()) throw ApiException.badRequest("Invalid field type");
            if (value.asText().length() > maxLength) throw ApiException.badRequest("Field is too long");
            result.set(field, value);
        });
        return result;
    }
}
