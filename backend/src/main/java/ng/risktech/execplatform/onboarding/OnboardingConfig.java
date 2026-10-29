package ng.risktech.execplatform.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OnboardingConfig {
    @Bean
    FieldEncryptionService fieldEncryptionService(@Value("${app.encryption-key}") String key) {
        return new FieldEncryptionService(key);
    }

    @Bean
    SubmissionValidator submissionValidator() {
        return new SubmissionValidator();
    }
}
