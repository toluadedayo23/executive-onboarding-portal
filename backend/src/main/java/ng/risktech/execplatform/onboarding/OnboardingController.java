package ng.risktech.execplatform.onboarding;

import jakarta.validation.Valid;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.onboarding.OnboardingDtos.DraftResponse;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveRequest;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveResponse;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SensitiveView;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SubmitRequest;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SubmitResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
@PreAuthorize("hasRole('EXECUTIVE')")
public class OnboardingController {
    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @GetMapping
    public DraftResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(user);
    }

    @PutMapping("/steps/{step}")
    public ResponseEntity<SaveResponse> save(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String step,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @Valid @RequestBody SaveRequest request) {
        IdempotencyService.Outcome outcome = service.saveStep(user, step, request, IdempotencyService.parseKey(key));
        var response = ResponseEntity.ok();
        if (outcome.replayed()) response.header("Idempotent-Replay", "true");
        return response.body(outcome.body());
    }

    @PostMapping("/submit")
    public SubmitResponse submit(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody SubmitRequest request) {
        return service.submit(user, request.version());
    }

    @GetMapping("/sensitive")
    public SensitiveView sensitive(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.sensitive(user);
    }
}
