package ng.risktech.execplatform.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import ng.risktech.execplatform.auth.AuthService;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DocumentDeleteIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
        uploadDir = Files.createTempDirectory("uploads");
        r.add("app.upload-dir", uploadDir::toString);
    }

    static Path uploadDir;

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @Test
    void anExecutiveCanRemoveTheirOwnDocumentWhileEditableButNotSomeoneElses() throws Exception {
        User owner = executive("doc-owner@example.test");
        User other = executive("doc-other@example.test");
        var file = new MockMultipartFile("file", "wrong-cv.pdf", "application/pdf", "%PDF-1.4\nsynthetic".getBytes());
        String uploaded = mvc.perform(as(multipart("/api/documents").file(file).param("type", "CV"), owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(uploaded).get("id").asText());
        Path stored = uploadDir.resolve(db.queryForObject("SELECT stored_name FROM documents WHERE id = ?", String.class, id));
        assertThat(stored).exists();

        mvc.perform(as(delete("/api/documents/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/documents/" + id), owner)).andExpect(status().isNoContent());

        mvc.perform(as(get("/api/documents"), owner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(delete("/api/documents/" + id), owner)).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT deleted_at IS NOT NULL FROM documents WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(stored).doesNotExist();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DOCUMENT_DELETED' AND entity_id = ?",
                Integer.class, id.toString())).isEqualTo(1);
    }

    private User executive(String email) {
        User u = auth.create(email, "VaultDocPass12", "EXECUTIVE");
        db.update("INSERT INTO consents(user_id) VALUES (?)", u.getId());
        return u;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        var principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        return request.with(authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_EXECUTIVE"))))).with(csrf());
    }
}
