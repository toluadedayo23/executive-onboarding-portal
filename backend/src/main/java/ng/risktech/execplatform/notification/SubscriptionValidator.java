package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.common.ApiException;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class SubscriptionValidator {
    static final int MAX_ENDPOINT_LENGTH = 500;

    private SubscriptionValidator() {
    }

    public static void validate(String endpoint, String p256dh, String auth, List<String> allowedHosts) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_ENDPOINT_LENGTH) throw invalid();
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (Exception e) {
            throw invalid();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw invalid();
        }
        if (!hostAllowed(uri.getHost(), allowedHosts)) throw invalid();
        byte[] key = decode(p256dh);
        if (key == null || key.length != 65 || key[0] != 0x04) throw invalid();
        byte[] secret = decode(auth);
        if (secret == null || secret.length != 16) throw invalid();
    }

    static boolean hostAllowed(String host, List<String> allowedHosts) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String rule : allowedHosts) {
            String r = rule.trim().toLowerCase(Locale.ROOT);
            if (r.startsWith("*.")) {
                if (h.endsWith(r.substring(1))) return true;
            } else if (h.equals(r)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] decode(String value) {
        if (value == null || value.length() > 200 || !value.matches("[A-Za-z0-9_-]+=*")) return null;
        try {
            return Base64.getUrlDecoder().decode(value.replace("=", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("This push subscription is not supported");
    }
}
