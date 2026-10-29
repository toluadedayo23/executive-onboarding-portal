package ng.risktech.execplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity h, JwtDecoder decoder, JwtDbAuthenticationConverter converter, CorsConfigurationSource cors, AuthenticationEntryPoint entry, AccessDeniedHandler denied, CookieBearerTokenResolver resolver, CsrfTokenRepository csrf) throws Exception {
        return h.csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())).cors(x -> x.configurationSource(cors)).sessionManagement(x -> x.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).exceptionHandling(x -> x.authenticationEntryPoint(entry).accessDeniedHandler(denied)).headers(x -> x.contentSecurityPolicy(y -> y.policyDirectives("default-src 'none'; frame-ancestors 'none'")).contentTypeOptions(y -> {
        }).frameOptions(y -> y.deny()).referrerPolicy(y -> y.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)).permissionsPolicy(y -> y.policy("camera=(), microphone=(), geolocation=()"))).oauth2ResourceServer(x -> x.jwt(y -> y.decoder(decoder).jwtAuthenticationConverter(converter)).bearerTokenResolver(resolver).authenticationEntryPoint(entry)).authorizeHttpRequests(x -> x.requestMatchers(CookieBearerTokenResolver.PUBLIC_PATHS.toArray(String[]::new)).permitAll().requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated()).build();
    }

    @Bean
    CookieBearerTokenResolver resolver(@Value("${app.auth-cookie}") String name) {
        return new CookieBearerTokenResolver(name);
    }

    @Bean
    CorsConfigurationSource cors(@Value("${app.frontend-origin}") String origin) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowCredentials(true);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }

    @Bean
    AuthenticationEntryPoint entry() {
        return (req, res, e) -> JsonErrors.write(res, 401, "Unauthorized", req.getRequestURI(), "Authentication required");
    }

    @Bean
    AccessDeniedHandler denied() {
        return (req, res, e) -> JsonErrors.write(res, 403, "Forbidden", req.getRequestURI(), "Access denied");
    }

    @Bean
    CsrfTokenRepository csrf(@Value("${app.cookie-secure}") boolean secure) {
        CookieCsrfTokenRepository r = CookieCsrfTokenRepository.withHttpOnlyFalse();
        r.setCookieName("XSRF-TOKEN");
        r.setHeaderName("X-XSRF-TOKEN");
        r.setCookieCustomizer(c -> c.path("/").sameSite("Strict").secure(secure));
        return r;
    }
}
