package ng.risktech.execplatform.user;

import jakarta.validation.Valid;
import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.auth.AuthDtos;
import ng.risktech.execplatform.auth.AuthService;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final UserRepository repo;
    private final AuthService auth;
    private final AuditRecorder audit;

    public AdminUserController(UserRepository r, AuthService a, AuditRecorder audit) {
        repo = r;
        auth = a;
        this.audit = audit;
    }

    @GetMapping
    java.util.Map<String, Object> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        Page<AuthDtos.AdminUserResponse> result = repo.findAll(PageRequest.of(page, size, org.springframework.data.domain.Sort.by("createdAt")))
                .map(u -> new AuthDtos.AdminUserResponse(u.getId(), u.getEmail(), u.getRole(), u.isActive(), u.getCreatedAt()));
        return java.util.Map.of("content", result.getContent(), "page", page, "size", size, "totalElements", result.getTotalElements());
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    AuthDtos.UserResponse create(@Valid @RequestBody AuthDtos.AdminCreate in) {
        if (!Set.of("ADMIN", "CONSULTANT").contains(in.role()))
            throw new IllegalArgumentException("Role is not allowed");
        User u = auth.create(in.email(), in.password(), in.role());
        audit.record(AuditEvent.of(AuditAction.USER_CREATED, AuditEntityType.USER, u.getId().toString(), Map.of("targetRole", u.getRole())));
        return new AuthDtos.UserResponse(u.getId(), u.getEmail(), u.getRole());
    }

    @PatchMapping("/{id}/active")
    ResponseEntity<Void> active(@PathVariable UUID id, @Valid @RequestBody AuthDtos.ActiveChange in, @AuthenticationPrincipal AuthenticatedUser actor) {
        auth.setActive(id, in.active(), actor.id());
        return ResponseEntity.noContent().build();
    }
}
