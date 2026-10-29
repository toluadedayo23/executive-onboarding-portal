package ng.risktech.execplatform.vetting;

import jakarta.validation.Valid;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.document.DocumentResponses;
import ng.risktech.execplatform.document.DocumentService;
import ng.risktech.execplatform.vetting.VettingDtos.CaseView;
import ng.risktech.execplatform.vetting.VettingDtos.NoteRequest;
import ng.risktech.execplatform.vetting.VettingDtos.QueueItem;
import ng.risktech.execplatform.vetting.VettingDtos.TransitionRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/vetting")
public class VettingController {
    private final VettingService service;
    private final DocumentService documents;

    public VettingController(VettingService service, DocumentService documents) {
        this.service = service;
        this.documents = documents;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('CONSULTANT','ADMIN')")
    public List<QueueItem> queue(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) VettingStatus status) {
        return service.queue(user, status);
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasAnyRole('CONSULTANT','ADMIN')")
    public CaseView detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.detail(user, id);
    }

    @PostMapping("/cases/{id}/transition")
    @PreAuthorize("hasRole('CONSULTANT')")
    public CaseView transition(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                               @Valid @RequestBody TransitionRequest request) {
        return service.transition(user, id, request.status(), request.reason());
    }

    @PostMapping("/cases/{id}/notes")
    @PreAuthorize("hasRole('CONSULTANT')")
    public CaseView addNote(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                            @Valid @RequestBody NoteRequest request) {
        service.addNote(user, id, request.note());
        return service.detail(user, id);
    }

    @GetMapping("/cases/{caseId}/documents/{documentId}/download")
    @PreAuthorize("hasAnyRole('CONSULTANT','ADMIN')")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID caseId,
                                             @PathVariable UUID documentId) {
        service.requireVisible(user, caseId);
        return DocumentResponses.attachment(documents.downloadForCase(caseId, documentId));
    }
}
