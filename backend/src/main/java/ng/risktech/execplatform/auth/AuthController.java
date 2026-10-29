package ng.risktech.execplatform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    private final AuditRecorder audit;
    private final IpRateLimiter limiter;
    private final org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    private final String issuer, cookie;
    private final long ttl;
    private final boolean secure;

    public AuthController(AuthService s, IpRateLimiter l, AuditRecorder a, org.springframework.security.oauth2.jwt.JwtEncoder e, @Value("${app.jwt-issuer}") String i, @Value("${app.auth-cookie}") String c, @Value("${app.jwt-ttl-minutes}") long t, @Value("${app.cookie-secure}") boolean sec) {
        service = s;
        audit = a;
        limiter = l;
        encoder = e;
        issuer = i;
        cookie = c;
        ttl = t;
        secure = sec;
    }

    private static AuthDtos.UserResponse view(User u) {
        return new AuthDtos.UserResponse(u.getId(), u.getEmail(), u.getRole());
    }

    @GetMapping("/csrf")
    ResponseEntity<Void> csrf(HttpServletRequest req) {
        CsrfToken t = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
        if (t != null) t.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    ResponseEntity<AuthDtos.UserResponse> register(@Valid @RequestBody AuthDtos.Credentials in, HttpServletRequest req) {
        if (!limiter.allow(req.getRemoteAddr())) throw ApiException.rateLimited();
        User u = service.create(in.email(), in.password(), "EXECUTIVE");
        audit.recordStandalone(new AuditEvent(AuditAction.USER_REGISTERED, AuditEntityType.USER, u.getId().toString(), null, u.getId(), u.getRole()));
        return ResponseEntity.status(201).body(view(u));
    }

    @PostMapping("/login")
    AuthDtos.UserResponse login(@Valid @RequestBody AuthDtos.Credentials in, HttpServletRequest req, HttpServletResponse res) {
        if (!limiter.allow(req.getRemoteAddr())) throw ApiException.rateLimited();
        User u = service.login(in.email(), in.password());
        String token = TokenService.issue(encoder, new TokenService.UserView(u.getId(), u.getTokenVersion()), issuer, ttl * 60);
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookie, token).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(Duration.ofMinutes(ttl)).build().toString());
        return view(u);
    }

    @PreAuthorize("hasAnyRole('EXECUTIVE','CONSULTANT','ADMIN')")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser p, HttpServletResponse res) {
        service.logout(service.current(p.id()));
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookie, "").httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(Duration.ZERO).build().toString());
        return ResponseEntity.noContent().build();
    }

    public record DeleteRequest(String currentPassword, boolean confirmed) {}
    public record PasswordChangeRequest(String currentPassword, String newPassword, String confirmation) {}
    @PreAuthorize("hasAnyRole('EXECUTIVE','CONSULTANT','ADMIN')")
    @PostMapping("/password")
    ResponseEntity<Void> password(@AuthenticationPrincipal AuthenticatedUser p, @Valid @RequestBody PasswordChangeRequest in) {
        if (in.newPassword() == null || !in.newPassword().equals(in.confirmation())) throw ApiException.badRequest("Passwords do not match");
        service.changePassword(service.current(p.id()), in.currentPassword(), in.newPassword());
        return ResponseEntity.noContent().build();
    }
    @PreAuthorize("hasRole('EXECUTIVE')")
    @PostMapping("/delete")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser p, @Valid @RequestBody DeleteRequest in, HttpServletResponse res) {
        if (!in.confirmed()) throw ApiException.badRequest("Deletion confirmation is required");
        service.deleteExecutive(service.current(p.id()), in.currentPassword());
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookie, "").httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(Duration.ZERO).build().toString());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAnyRole('EXECUTIVE','CONSULTANT','ADMIN')")
    @GetMapping("/me")
    AuthDtos.UserResponse me(@AuthenticationPrincipal AuthenticatedUser p) {
        return new AuthDtos.UserResponse(p.id(), p.email(), p.role());
    }
}
