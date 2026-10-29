package ng.risktech.execplatform.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String role) {
}
