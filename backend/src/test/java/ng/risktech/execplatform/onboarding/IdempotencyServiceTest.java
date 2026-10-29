package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveRequest;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveResponse;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final IdempotencyRepository repository = mock(IdempotencyRepository.class);
    final IdempotencyService service = new IdempotencyService(repository, json, Base64.getEncoder().encodeToString(new byte[32]), 168);
    final UUID user = UUID.randomUUID();
    final UUID key = UUID.randomUUID();
    final SaveResponse saved = new SaveResponse(4, 2, Map.of("profile", "COMPLETE"));

    @Test
    void sameKeyAndSameRequestReplaysTheStoredResponseWithoutSavingAgain() throws Exception {
        when(repository.find(user, key)).thenReturn(Optional.of(new IdempotencyRepository.Stored("h1", 200, json.writeValueAsString(saved))));
        AtomicInteger saves = new AtomicInteger();

        var outcome = service.run(user, key, "h1", () -> {
            saves.incrementAndGet();
            return saved;
        });

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.body()).isEqualTo(saved);
        assertThat(saves).hasValue(0);
    }

    @Test
    void sameKeyWithADifferentRequestIs422() {
        when(repository.find(user, key)).thenReturn(Optional.of(new IdempotencyRepository.Stored("h1", 200, "{}")));
        assertThatThrownBy(() -> service.run(user, key, "h2", () -> saved))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void onlySuccessfulSavesAreStored() {
        when(repository.find(user, key)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.run(user, key, "h1", () -> {
            throw ApiException.conflict("VERSION_CONFLICT", "stale");
        }))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).insert(any(), any(), anyString(), anyInt(), anyString());

        var outcome = service.run(user, key, "h1", () -> saved);
        assertThat(outcome.replayed()).isFalse();
        verify(repository).insert(any(), any(), anyString(), anyInt(), anyString());
    }

    @Test
    void aKeyThatIsNotAUuidIs400() {
        assertThat(IdempotencyService.parseKey(null)).isNull();
        assertThatThrownBy(() -> IdempotencyService.parseKey("not-a-uuid"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("BAD_REQUEST");
    }

    @Test
    void hmacIgnoresKeyOrderButChangesWithTheData() throws Exception {
        var a = new SaveRequest(3, 1, json.readTree("{\"fullName\":\"Ada\",\"country\":\"Nigeria\"}"));
        var sameReordered = new SaveRequest(3, 1, json.readTree("{\"country\":\"Nigeria\",\"fullName\":\"Ada\"}"));
        var different = new SaveRequest(3, 1, json.readTree("{\"fullName\":\"Ade\",\"country\":\"Nigeria\"}"));
        String h = service.requestHmac("profile", a);
        assertThat(h).matches("[0-9a-f]{64}");
        assertThat(service.requestHmac("profile", sameReordered)).isEqualTo(h);
        assertThat(service.requestHmac("profile", different)).isNotEqualTo(h);
        assertThat(service.requestHmac("experience", a)).isNotEqualTo(h);
    }
}
