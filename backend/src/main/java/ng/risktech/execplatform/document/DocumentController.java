package ng.risktech.execplatform.document;

import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.document.DocumentDtos.DocumentView;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@PreAuthorize("hasRole('EXECUTIVE')")
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    public List<DocumentView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.listOwn(user);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentView upload(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam String type,
                               @RequestParam(required = false) String identificationType,
                               @RequestParam(required = false) String certificationName,
                               @RequestPart("file") MultipartFile file) {
        return service.upload(user, type, identificationType, certificationName, file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.deleteOwn(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return DocumentResponses.attachment(service.downloadOwn(user, id));
    }
}
