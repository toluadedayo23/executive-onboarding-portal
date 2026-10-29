package ng.risktech.execplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class JsonErrors {
    private JsonErrors() {
    }

    public static void write(HttpServletResponse r, int status, String error, String path, String message) throws java.io.IOException {
        r.setStatus(status);
        r.setContentType("application/json");
        r.setHeader("Cache-Control", "no-store");
        new ObjectMapper().writeValue(r.getOutputStream(), Map.of("timestamp", OffsetDateTime.now().toString(), "status", status, "error", error, "message", message, "path", path, "traceId", UUID.randomUUID().toString()));
    }
}
