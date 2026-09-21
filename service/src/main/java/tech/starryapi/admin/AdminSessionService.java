package tech.starryapi.admin;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;

@Service
public class AdminSessionService {

    private final ProxyProperties properties;
    private final Map<String, AdminPrincipal> sessions = new ConcurrentHashMap<>();

    public AdminSessionService(ProxyProperties properties) {
        this.properties = properties;
    }

    public SessionToken create(AdminUserEntity user) {
        Instant expiresAt = Instant.now().plus(properties.getAdminSessionTtl());
        String token = "adm-" + Hashing.randomToken(32);
        boolean firstLogin = Boolean.TRUE.equals(user.getFirstLogin());
        sessions.put(token, new AdminPrincipal(user.getUserId(), user.getUsername(), user.getRole(), expiresAt, firstLogin));
        return new SessionToken(token, expiresAt);
    }

    public AdminPrincipal verify(String token) {
        AdminPrincipal principal = sessions.get(token);
        if (principal == null || principal.expiresAt().isBefore(Instant.now())) {
            if (principal != null) {
                sessions.remove(token);
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Invalid or expired admin session");
        }
        return principal;
    }

    public void invalidateAllSessions() {
        sessions.clear();
    }

    public void invalidateSession(String token) {
        if (token != null) sessions.remove(token);
    }

    public void invalidateUserSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        sessions.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId()));
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public record SessionToken(String token, Instant expiresAt) {
    }
}
