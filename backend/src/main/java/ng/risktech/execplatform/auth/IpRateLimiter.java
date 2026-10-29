package ng.risktech.execplatform.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IpRateLimiter {
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit = 20;

    public IpRateLimiter() {
        this(Clock.systemUTC());
    }

    public IpRateLimiter(Clock c) {
        clock = c;
    }

    public synchronized boolean allow(String ip) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> e.getValue().until.isBefore(now));
        Window w = windows.computeIfAbsent(ip, k -> new Window(now.plusSeconds(60)));
        if (w.count >= limit) return false;
        w.count++;
        return true;
    }

    static class Window {
        Instant until;
        int count;

        Window(Instant u) {
            until = u;
        }
    }
}
