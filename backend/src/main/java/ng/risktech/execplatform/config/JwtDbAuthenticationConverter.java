package ng.risktech.execplatform.config;

import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.user.User;
import ng.risktech.execplatform.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class JwtDbAuthenticationConverter implements Converter<Jwt, org.springframework.security.authentication.AbstractAuthenticationToken> {
    private final UserRepository repo;

    public JwtDbAuthenticationConverter(UserRepository r) {
        repo = r;
    }

    public org.springframework.security.authentication.AbstractAuthenticationToken convert(Jwt jwt) {
        User u = repo.findById(UUID.fromString(jwt.getSubject())).filter(User::isActive).orElseThrow(() -> new BadCredentialsException("Invalid token"));
        Number tv = jwt.getClaim("tv");
        if (tv == null || tv.intValue() != u.getTokenVersion()) throw new BadCredentialsException("Invalid token");
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(u.getId(), u.getEmail(), u.getRole()), jwt, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole())));
    }
}
