package ng.risktech.execplatform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record Credentials(@NotBlank @Email @Size(max = 254) String email, @NotNull String password) {
    }

    public record AdminCreate(@NotBlank @Email @Size(max = 254) String email, @NotNull String password,
                              @NotBlank String role) {
    }

    public record ActiveChange(@NotNull Boolean active) {
    }

    public record UserResponse(UUID id, String email, String role) {
    }

    public record AdminUserResponse(UUID id, String email, String role, boolean active,
                                    java.time.OffsetDateTime createdAt) {
    }
}
