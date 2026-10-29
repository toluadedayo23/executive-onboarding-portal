package ng.risktech.execplatform.consent;

import jakarta.servlet.http.HttpServletRequest;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/consent")
@PreAuthorize("hasRole('EXECUTIVE')")
public class ConsentController {
    private final ConsentService service;

    public ConsentController(ConsentService service) {
        this.service = service;
    }

    @GetMapping
    public ConsentService.Status status(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.status(user.id());
    }

    @PostMapping
    public ResponseEntity<Void> grant(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody ConsentRequest request,
                                      HttpServletRequest http) {
        if (!request.granted()) throw ApiException.badRequest("Consent must be explicitly granted");
        service.grant(user, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    public record ConsentRequest(boolean granted) {
    }
}
