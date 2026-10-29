package ng.risktech.execplatform.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import java.util.Set;

public class CookieBearerTokenResolver implements BearerTokenResolver {
    static final Set<String> PUBLIC_PATHS = Set.of("/api/health", "/api/auth/csrf", "/api/auth/register", "/api/auth/login");

    private final String name;

    public CookieBearerTokenResolver(String name) {
        this.name = name;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        // A stale cookie must not block sign-in
        if (PUBLIC_PATHS.contains(request.getRequestURI())) return null;
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
