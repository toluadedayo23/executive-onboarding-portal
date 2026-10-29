package ng.risktech.execplatform.document;

import org.springframework.core.io.Resource;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class DocumentDtos {
    private DocumentDtos() {
    }

    public record DocumentView(UUID id, String type, String originalName, String contentType, long sizeBytes,
                               OffsetDateTime uploadedAt, String identificationType, String certificationName) {
    }

    public record Download(Resource resource, String contentType, String filename) {
    }
}
