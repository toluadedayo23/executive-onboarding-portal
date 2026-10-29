package ng.risktech.execplatform.onboarding;

import java.util.List;

public class OnboardingValidationException extends RuntimeException {
    private final List<OnboardingDtos.Problem> problems;

    public OnboardingValidationException(List<OnboardingDtos.Problem> problems) {
        super("Validation failed");
        this.problems = problems;
    }

    public List<OnboardingDtos.Problem> problems() {
        return problems;
    }
}
