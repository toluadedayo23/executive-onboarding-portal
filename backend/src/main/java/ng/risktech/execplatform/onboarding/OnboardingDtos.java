package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class OnboardingDtos {
    private OnboardingDtos() {
    }

    public record SaveRequest(@Min(1) int version, @Min(0) @Max(5) Integer currentStep, @NotNull JsonNode data) {
    }

    public record SubmitRequest(@Min(1) int version) {
    }

    public record DraftResponse(String status, boolean editable, int version, Integer currentStep,
                                Map<String, String> stepStatus, OffsetDateTime submittedAt,
                                String moreInfoMessage, JsonNode data, List<Problem> problems) {
    }

    public record SaveResponse(int version, Integer currentStep, Map<String, String> stepStatus) {
    }

    public record SubmitResponse(String status, OffsetDateTime submittedAt) {
    }

    public record Problem(String step, String field, String code, Integer item) {
        public Problem(String step, String field, String code) {
            this(step, field, code, null);
        }

        Problem atItem(int index) {
            return new Problem(step, field, code, index);
        }
    }

    public record SensitiveView(String nationalId, List<RefereeContact> referees) {
    }

    public record RefereeContact(String name, String relationship, String email, String phone) {
    }
}
