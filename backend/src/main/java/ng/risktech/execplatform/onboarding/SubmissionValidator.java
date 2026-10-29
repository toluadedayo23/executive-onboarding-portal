package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import ng.risktech.execplatform.onboarding.OnboardingDtos.Problem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class SubmissionValidator {
    private static final Pattern PHONE = Pattern.compile("[0-9+ ()-]{7,20}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText().trim();
    }

    public List<Problem> validate(JsonNode draft, String accountEmail) {
        List<Problem> problems = new ArrayList<>();
        for (String step : DraftShape.STEPS) problems.addAll(validateStep(step, draft, accountEmail));
        return problems;
    }

    public List<Problem> validateStep(String step, JsonNode draft, String accountEmail) {
        List<Problem> p = new ArrayList<>();
        switch (step) {
            case "profile" -> profile(p, draft.path("profile"));
            case "experience" -> items(p, draft, step, 1, 20, item -> experience(p, item));
            case "sectors" -> {
                items(p, draft, step, 1, 10, item -> sector(p, item));
                noDuplicates(p, draft, step, "sector");
            }
            case "qualifications" -> items(p, draft, step, 1, 15, item -> qualification(p, item));
            case "referees" -> {
                items(p, draft, step, 2, 5, item -> referee(p, item, accountEmail));
                noDuplicates(p, draft, step, "email");
            }
            default -> throw new IllegalArgumentException("Unknown step");
        }
        return p;
    }

    private void profile(List<Problem> p, JsonNode profile) {
        required(p, "profile", profile, "fullName", 2, 120);
        required(p, "profile", profile, "headline", 3, 120);
        required(p, "profile", profile, "summary", 50, 2000);
        required(p, "profile", profile, "country", 2, 80);
        required(p, "profile", profile, "phone", 7, 20);
        String phone = text(profile, "phone");
        if (phone != null && !PHONE.matcher(phone).matches()) p.add(new Problem("profile", "phone", "INVALID_FORMAT"));
    }

    private void experience(List<Problem> p, JsonNode item) {
        String type = text(item, "type");
        if (!"EXECUTIVE".equals(type) && !"BOARD".equals(type)) p.add(new Problem("experience", "type", "REQUIRED"));
        required(p, "experience", item, "organisation", 2, 120);
        required(p, "experience", item, "title", 2, 120);
        required(p, "experience", item, "description", 10, 2000);
        LocalDate start = date(p, item, "startDate", true);
        LocalDate end = date(p, item, "endDate", false);
        if (start != null && start.isAfter(LocalDate.now())) p.add(new Problem("experience", "startDate", "IN_FUTURE"));
        if (start != null && end != null && end.isBefore(start))
            p.add(new Problem("experience", "endDate", "BEFORE_START"));
    }

    private void sector(List<Problem> p, JsonNode item) {
        required(p, "sectors", item, "sector", 2, 80);
        int years = item.path("yearsExperience").asInt(-1);
        if (years < 0 || years > 60) p.add(new Problem("sectors", "yearsExperience", "OUT_OF_RANGE"));
    }

    private void qualification(List<Problem> p, JsonNode item) {
        required(p, "qualifications", item, "title", 2, 120);
        required(p, "qualifications", item, "institution", 2, 120);
        int year = item.path("yearObtained").asInt(-1);
        if (year < 1950 || year > LocalDate.now().getYear())
            p.add(new Problem("qualifications", "yearObtained", "OUT_OF_RANGE"));
    }

    private void referee(List<Problem> p, JsonNode item, String accountEmail) {
        required(p, "referees", item, "name", 2, 120);
        required(p, "referees", item, "relationship", 2, 80);
        required(p, "referees", item, "phone", 7, 20);
        String email = text(item, "email");
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            p.add(new Problem("referees", "email", "INVALID_FORMAT"));
        } else if (email.equalsIgnoreCase(accountEmail)) {
            p.add(new Problem("referees", "email", "SELF_REFERENCE"));
        }
    }

    private void items(List<Problem> p, JsonNode draft, String step, int min, int max, ItemCheck check) {
        JsonNode items = draft.path(step).path("items");
        int count = items.isArray() ? items.size() : 0;
        if (count < min) p.add(new Problem(step, "items", "MIN_ITEMS"));
        if (count > max) p.add(new Problem(step, "items", "MAX_ITEMS"));
        for (int i = 0; i < count; i++) {
            int before = p.size();
            check.check(items.get(i));
            for (int j = before; j < p.size(); j++) p.set(j, p.get(j).atItem(i));
        }
    }

    private void noDuplicates(List<Problem> p, JsonNode draft, String step, String field) {
        Set<String> seen = new HashSet<>();
        JsonNode items = draft.path(step).path("items");
        for (int i = 0; i < items.size(); i++) {
            String value = text(items.get(i), field);
            if (value != null && !seen.add(value.toLowerCase(Locale.ROOT)))
                p.add(new Problem(step, field, "DUPLICATE", i));
        }
    }

    private void required(List<Problem> p, String step, JsonNode node, String field, int min, int max) {
        String value = text(node, field);
        if (value == null || value.isBlank()) p.add(new Problem(step, field, "REQUIRED"));
        else if (value.length() < min) p.add(new Problem(step, field, "TOO_SHORT"));
        else if (value.length() > max) p.add(new Problem(step, field, "TOO_LONG"));
    }

    private LocalDate date(List<Problem> p, JsonNode item, String field, boolean required) {
        String value = text(item, field);
        if (value == null || value.isBlank()) {
            if (required) p.add(new Problem("experience", field, "REQUIRED"));
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            p.add(new Problem("experience", field, "INVALID_FORMAT"));
            return null;
        }
    }

    private interface ItemCheck {
        void check(JsonNode item);
    }
}
