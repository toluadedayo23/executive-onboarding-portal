package ng.risktech.execplatform.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class PasswordPolicy {
    private static final Set<String> BLOCKED = Set.of("password", "password123", "123456789012", "qwertyuiop", "letmein123", "welcome123", "admin123456", "iloveyou123", "changeme123", "monkey123456", "football123", "abc123456789", "password1!", "qwerty123456", "secret123456", "111111111111", "000000000000", "Passw0rd1234", "P@ssw0rd123", "adminadmin12");

    public void validate(String password, String email) {
        if (password == null || password.length() < 12)
            throw new IllegalArgumentException("Password does not meet policy");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password does not meet policy");
        String p = password.toLowerCase(), e = email.toLowerCase();
        String local = e.split("@", 2)[0];
        if (BLOCKED.contains(p) || p.equals(e) || (local.length() >= 4 && p.contains(local)))
            throw new IllegalArgumentException("Password does not meet policy");
    }
}
