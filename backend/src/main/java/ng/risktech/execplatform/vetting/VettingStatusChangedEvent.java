package ng.risktech.execplatform.vetting;

import java.util.UUID;

public record VettingStatusChangedEvent(UUID executiveUserId, VettingStatus newStatus) {
}
