package ng.risktech.execplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ng.risktech.execplatform.onboarding.DraftProtector;
import ng.risktech.execplatform.onboarding.FieldEncryptionService;
import ng.risktech.execplatform.onboarding.SubmissionValidator;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingUnitTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void encryptionUsesRandomIvAndAuthenticatesValueAndContext() {
        var service = new FieldEncryptionService(KEY);
        String a = service.encrypt("SYNTHETIC1234", "profile.nationalId");
        String b = service.encrypt("SYNTHETIC1234", "profile.nationalId");
        assertThat(a).startsWith("v1:").isNotEqualTo(b);
        assertThat(service.decrypt(a, "profile.nationalId")).isEqualTo("SYNTHETIC1234");
        assertThatThrownBy(() -> service.decrypt(a, "referee.email")).isInstanceOf(IllegalStateException.class);
        String tampered = a.substring(0, a.length() - 1) + (a.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.decrypt(tampered, "profile.nationalId")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidKeyIsRejected() {
        assertThatThrownBy(() -> new FieldEncryptionService("bad")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maskedValuesRoundTripWithoutOverwritingStoredSecrets() throws Exception {
        var protector = new DraftProtector(new FieldEncryptionService(KEY));
        JsonNode empty = json.createObjectNode();
        var firstSave = json.createObjectNode();
        firstSave.set("referees", protector.protectStep("referees", json.readTree("""
                {"items":[{"id":"r1","name":"Synthetic Referee","email":"referee@example.test","phone":"+234 800 000 0001"}]}
                """), empty));
        firstSave.set("profile", protector.protectStep("profile", json.readTree("{\"nationalId\":\"SYNTHETIC1234\"}"), empty));

        JsonNode masked = protector.maskForOwner(firstSave);
        assertThat(masked.at("/profile/nationalId").asText()).isEqualTo("••••1234");
        assertThat(masked.at("/referees/items/0/email").asText()).isEqualTo("••••@example.test");

        var secondSave = firstSave.deepCopy();
        secondSave.set("referees", protector.protectStep("referees", masked.get("referees"), firstSave));
        secondSave.set("profile", protector.protectStep("profile", masked.get("profile"), firstSave));
        var thirdSave = secondSave.deepCopy();
        thirdSave.set("profile", protector.protectStep("profile", json.readTree("{\"nationalId\":\"On file\"}"), secondSave));
        assertThat(protector.decrypt(thirdSave).at("/profile/nationalId").asText()).isEqualTo("SYNTHETIC1234");

        JsonNode plain = protector.decrypt(secondSave);
        assertThat(plain.at("/profile/nationalId").asText()).isEqualTo("SYNTHETIC1234");
        assertThat(plain.at("/referees/items/0/email").asText()).isEqualTo("referee@example.test");
        assertThat(plain.at("/referees/items/0/phone").asText()).isEqualTo("+234 800 000 0001");
    }

    @Test
    void problemsNameTheItemSoTheUiCanExplainThem() throws Exception {
        var root = json.readTree("""
                {"qualifications":{"items":[{"title":"MBA","institution":"Example University","yearObtained":17}]},
                 "referees":{"items":[{"name":"Ref One","relationship":"Friend","email":"same@example.test","phone":"+234 800 000 0001"},
                                      {"name":"Ref Two","relationship":"Friend","email":"SAME@example.test","phone":"+234 800 000 0002"}]}}
                """);
        var validator = new SubmissionValidator();
        assertThat(validator.validateStep("qualifications", root, "exec@example.test"))
                .extracting("field", "code", "item").containsExactly(org.assertj.core.groups.Tuple.tuple("yearObtained", "OUT_OF_RANGE", 0));
        assertThat(validator.validateStep("referees", root, "exec@example.test"))
                .extracting("field", "code", "item").containsExactly(org.assertj.core.groups.Tuple.tuple("email", "DUPLICATE", 1));
    }

    @Test
    void submissionValidatorRequiresTwoRefereesAndDoesNotEchoValues() throws Exception {
        var root = json.readTree("""
                {"profile":{"fullName":"Synthetic Executive","headline":"Governance Leader","summary":"This synthetic summary is deliberately long enough to satisfy the completeness requirement for the test.","phone":"+234 800 000 0000","country":"Nigeria"},
                 "experience":{"items":[{"type":"BOARD","organisation":"Example Org","title":"Leader","startDate":"2020-01-01","description":"Synthetic board role."}]},
                 "sectors":{"items":[{"sector":"Technology","yearsExperience":4}]},
                 "qualifications":{"items":[{"title":"MBA","institution":"Example University","yearObtained":2020}]},
                 "referees":{"items":[{"name":"Synthetic Referee","relationship":"Colleague","email":"referee@example.test","phone":"+234 800 000 0001"}]}}
                """);
        var problems = new SubmissionValidator().validate(root, "exec@example.test");
        assertThat(problems).extracting("step", "code").containsExactly(org.assertj.core.groups.Tuple.tuple("referees", "MIN_ITEMS"));
        assertThat(problems.toString()).doesNotContain("referee@example.test");
    }
}
