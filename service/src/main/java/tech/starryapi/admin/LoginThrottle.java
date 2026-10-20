package tech.starryapi.admin;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import tech.starryapi.common.ApiException;

/**
 * Limits sign-in attempts. Password hashing (PBKDF2) is deliberately expensive, so an unthrottled login
 * endpoint is both a password-guessing target and a cheap way to burn CPU.
 *
 * <p>There is intentionally no per-username lockout: a known username such as the Root account would let anyone
 * lock the real owner out. Attempts are counted per client address and, as a backstop, across the whole site.
 */
@Component
public class LoginThrottle {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final int perClientPerMinute;
    private final int globalPerMinute;
    private final Clock clock;
    private final Map<String, Window> clients = new ConcurrentHashMap<>();
    private volatile Window global = new Window(0);

    @Autowired
    public LoginThrottle(
        @Value("${starry.auth.login-attempts-per-minute:20}") int perClientPerMinute,
        @Value("${starry.auth.login-global-attempts-per-minute:600}") int globalPerMinute
    ) {
        this(perClientPerMinute, globalPerMinute, Clock.systemUTC());
    }

    LoginThrottle(int perClientPerMinute, int globalPerMinute, Clock clock) {
        this.perClientPerMinute = perClientPerMinute;
        this.globalPerMinute = globalPerMinute;
        this.clock = clock;
    }

    /** Counts one sign-in attempt and fails with 429 when the client or the site is over its budget. */
    public void check(ServerWebExchange exchange) {
        long now = clock.millis();
        Window siteWindow = global;
        if (now - siteWindow.startMillis >= WINDOW_MILLIS) {
            siteWindow = new Window(now);
            global = siteWindow;
        }
        if (globalPerMinute > 0 && siteWindow.count.incrementAndGet() > globalPerMinute) {
            reject(exchange, siteWindow, now);
        }
        if (perClientPerMinute <= 0) {
            return;
        }
        if (clients.size() > MAX_TRACKED_CLIENTS) {
            clients.clear();
        }
        Window window = clients.compute(clientKey(exchange), (key, current) ->
            current == null || now - current.startMillis >= WINDOW_MILLIS ? new Window(now) : current);
        if (window.count.incrementAndGet() > perClientPerMinute) {
            reject(exchange, window, now);
        }
    }

    private void reject(ServerWebExchange exchange, Window window, long now) {
        long retryAfterSeconds = Math.max(1, (WINDOW_MILLIS - (now - window.startMillis) + 999) / 1000);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "Too many sign-in attempts. Try again later.");
    }

    /**
     * The address behind a reverse proxy (first X-Forwarded-For hop) when present, otherwise the socket address.
     * The header can be forged, which only lets a client dodge its own limit; the site-wide cap still applies.
     */
    static String clientKey(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        long now = clock.millis();
        clients.entrySet().removeIf(entry -> now - entry.getValue().startMillis >= WINDOW_MILLIS);
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
