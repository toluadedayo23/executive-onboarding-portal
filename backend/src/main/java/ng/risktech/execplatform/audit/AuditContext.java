package ng.risktech.execplatform.audit;

import ng.risktech.execplatform.auth.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Component
public class AuditContext {
    public UUID actorId() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthenticatedUser u) return u.id();
        return null;
    }

    public String actorRole() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthenticatedUser u) return u.role();
        return null;
    }

    public String ip() {
        var a = RequestContextHolder.getRequestAttributes();
        if (a instanceof ServletRequestAttributes s) return s.getRequest().getRemoteAddr();
        return null;
    }
}
