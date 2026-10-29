package ng.risktech.execplatform.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ng.risktech.execplatform.document.DocumentDtos.DocumentView;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VettingDtos {
    private VettingDtos() {
    }

    public record TransitionRequest(@NotNull VettingStatus status, @Size(max = 2000) String reason) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String note) {
    }

    public record QueueItem(UUID id, String executiveName, String headline, String status,
                            String assignedConsultantEmail, boolean assignedToMe,
                            OffsetDateTime submittedAt, OffsetDateTime updatedAt) {
    }

    public record NoteView(UUID id, String authorEmail, String note, OffsetDateTime createdAt) {
    }

    public record HistoryEntry(OffsetDateTime at, String event, String actorEmail, String actorRole) {
    }

    public record ActionView(String status, boolean messageRequired) {
    }

    public record CaseView(UUID id, String status, String executiveName, UUID assignedConsultantId,
                           String assignedConsultantEmail, boolean assignedToMe,
                           OffsetDateTime submittedAt, OffsetDateTime updatedAt, OffsetDateTime decidedAt,
                           String moreInfoMessage, String decisionReason, JsonNode profile,
                           List<DocumentView> documents, List<NoteView> notes, List<HistoryEntry> history,
                           List<ActionView> actions) {
    }
}
