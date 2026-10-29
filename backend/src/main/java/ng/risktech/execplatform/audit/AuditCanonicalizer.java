package ng.risktech.execplatform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class AuditCanonicalizer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private AuditCanonicalizer() {
    }

    public static String value(String s) {
        if (s == null) return "-1:";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return b.length + ":" + s;
    }

    public static String detail(Map<String, String> d) {
        if (d == null) return null;
        try {
            return new ObjectMapper().writeValueAsString(new TreeMap<>(d));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid detail");
        }
    }

    public static String canonical(UUID actor, String role, String action, String type, String entity, String detail, String ip, Instant created, String prev) {
        return "audit-v1" + value(actor == null ? null : actor.toString()) + value(role) + value(action) + value(type) + value(entity) + value(detail) + value(ip) + value(TIME.format(created.truncatedTo(java.time.temporal.ChronoUnit.MICROS))) + value(prev);
    }

    public static String hash(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder x = new StringBuilder();
            for (byte v : b) x.append(String.format("%02x", v));
            return x.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
