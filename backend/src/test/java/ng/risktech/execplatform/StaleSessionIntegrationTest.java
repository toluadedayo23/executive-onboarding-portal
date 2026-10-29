package ng.risktech.execplatform;

import jakarta.servlet.http.Cookie;
import ng.risktech.execplatform.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StaleSessionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static final Cookie STALE = new Cookie("EXEC_AUTH", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzdGFsZSJ9.not-a-valid-signature");
    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService auth;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
    }

    @Test
    void signInWorksDespiteAStaleCookieAndProtectedCallsGetTheStandardJson401() throws Exception {
        auth.create("stale-cookie@example.test", "VaultStaleCookie1", "EXECUTIVE");

        mvc.perform(post("/api/auth/login").cookie(STALE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stale-cookie@example.test\",\"password\":\"VaultStaleCookie1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"));

        mvc.perform(get("/api/auth/me").cookie(STALE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
}
