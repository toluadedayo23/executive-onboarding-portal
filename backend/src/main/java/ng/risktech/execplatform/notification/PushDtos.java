package ng.risktech.execplatform.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class PushDtos {
    private PushDtos() {
    }

    public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 200) String auth) {
    }

    public record SubscribeRequest(@NotBlank @Size(max = 500) String endpoint, @NotNull @Valid Keys keys) {
    }

    public record UnsubscribeRequest(@NotBlank @Size(max = 500) String endpoint) {
    }

    public record PublicKey(String publicKey) {
    }

    public record Subscribed(UUID id) {
    }
}
