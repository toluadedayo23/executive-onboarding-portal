package ng.risktech.execplatform.notification;

import jakarta.validation.Valid;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.notification.PushDtos.PublicKey;
import ng.risktech.execplatform.notification.PushDtos.SubscribeRequest;
import ng.risktech.execplatform.notification.PushDtos.Subscribed;
import ng.risktech.execplatform.notification.PushDtos.UnsubscribeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
@PreAuthorize("hasRole('EXECUTIVE')")
public class PushController {
    private final PushSubscriptionService service;

    public PushController(PushSubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/public-key")
    public PublicKey publicKey() {
        return new PublicKey(service.publicKey());
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Subscribed> subscribe(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody SubscribeRequest request) {
        PushSubscriptionService.Result result = service.subscribe(user, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(new Subscribed(result.id()));
    }

    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody UnsubscribeRequest request) {
        service.unsubscribe(user, request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
