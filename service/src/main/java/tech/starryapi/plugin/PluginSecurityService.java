package tech.starryapi.plugin;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.admin.AdminUserRepository;
import tech.starryapi.admin.PasswordHasher;
import tech.starryapi.admin.PluginVerificationMailer;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class PluginSecurityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AdminUserRepository users;
    private final PasswordHasher passwords;
    private final PluginVerificationMailer mailer;
    private final PluginStore store;

    public PluginSecurityService(AdminUserRepository users, PasswordHasher passwords,
                                 PluginVerificationMailer mailer, PluginStore store) {
        this.users = users;
        this.passwords = passwords;
        this.mailer = mailer;
        this.store = store;
    }

    public Mono<AdminUserEntity> root(AdminPrincipal principal) {
        return users.findApplicationRoot().filter(root -> principal != null
                && root.getUserId().equals(principal.userId()) && "ADMIN".equals(root.getRole())
                && Boolean.TRUE.equals(root.getEnabled()) && !Boolean.TRUE.equals(root.getFirstLogin()))
            .switchIfEmpty(Mono.error(error(HttpStatus.FORBIDDEN, "PLUGIN_ROOT_REQUIRED",
                "仅初始管理员 root 可以管理开发者模式和插件，请使用该账号登录")));
    }

    public Mono<Boolean> isRoot(AdminPrincipal principal) {
        return root(principal).map(ignored -> true).onErrorResume(ApiException.class, ignored -> Mono.just(false));
    }

    public Mono<PluginDtos.Challenge> send(AdminPrincipal actor, String sessionHash, String password) {
        return root(actor).flatMap(root -> {
            if (root.getEmail() == null || !root.getEmail().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                return Mono.error(error(HttpStatus.CONFLICT, "PLUGIN_ROOT_EMAIL_MISSING", "请先为 root 账号绑定有效邮箱"));
            }
            String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
            String id = UUID.randomUUID().toString();
            Instant expires = Instant.now().plusSeconds(600);
            return store.locked(state -> {
                Instant now = Instant.now();
                ApiException blocked = blocked(state, now);
                if (blocked != null) return Mono.just(new Decision(blocked));
                if (state.sentAt() != null && state.sentAt().plusSeconds(60).isAfter(now)) {
                    return Mono.just(new Decision(error(HttpStatus.TOO_MANY_REQUESTS,
                        "PLUGIN_CODE_COOLDOWN", "请等待 60 秒后再发送验证码")));
                }
                return matches(root, password).flatMap(valid -> {
                    if (!valid) return reject(state, actor, "PASSWORD_REJECTED", now);
                    return Mono.fromCallable(() -> {
                        String salt = passwords.newSalt();
                        return new String[] {salt, passwords.hash(code, salt)};
                    }).subscribeOn(Schedulers.boundedElastic()).flatMap(pair -> store.challenge(
                        id, pair[1], pair[0], sessionHash, identity(root), expires))
                        .then(store.audit("", actor.userId(), "CODE_REQUEST", "ACCEPTED", "root email challenge"))
                        .thenReturn(new Decision(null));
                });
            }).flatMap(decision -> decision.error() == null ? Mono.just(decision) : Mono.error(decision.error()))
                .flatMap(ignored -> mailer.send(root.getEmail(), code)
                    .then(store.delivered(id, true))
                    .then(store.audit("", actor.userId(), "CODE_DELIVERY", "SENT", "root email challenge"))
                    .thenReturn(new PluginDtos.Challenge(id, maskEmail(root.getEmail()), expires, 60))
                    .onErrorResume(failure -> store.delivered(id, false)
                        .then(store.audit("", actor.userId(), "CODE_DELIVERY", "FAILED", failure.getClass().getSimpleName()))
                        .then(Mono.error(failure))));
        });
    }

    public Mono<Void> enable(AdminPrincipal actor, String sessionHash, PluginDtos.Enable request) {
        if (!request.acceptRisk()) return Mono.error(error(HttpStatus.BAD_REQUEST,
            "PLUGIN_RISK_ACCEPTANCE_REQUIRED", "请先确认第三方插件的完整访问权限与运行风险"));
        return root(actor).flatMap(root -> store.locked(state -> {
            Instant now = Instant.now();
            ApiException blocked = blocked(state, now);
            if (blocked != null) return Mono.just(new Decision(blocked));
            if (!state.delivered() || !request.challengeId().equals(state.challengeId())
                || !sessionHash.equals(state.sessionHash()) || !identity(root).equals(state.identityHash())
                || state.expiresAt() == null || !state.expiresAt().isAfter(now)) {
                return reject(state, actor, "CHALLENGE_REJECTED", now);
            }
            return matches(root, request.password()).zipWith(Mono.fromCallable(() -> passwords.matches(
                request.code(), state.codeSalt(), state.codeHash())).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(valid -> !valid.getT1() || !valid.getT2()
                    ? reject(state, actor, "VERIFICATION_REJECTED", now)
                    : store.mode(true).then(store.audit("", actor.userId(), "DEVELOPER_MODE", "ENABLED",
                        "root password and email verified; full-trust code execution accepted"))
                        .thenReturn(new Decision(null)));
        })).flatMap(decision -> decision.error() == null ? Mono.empty() : Mono.error(decision.error()));
    }

    public Mono<Void> disable(AdminPrincipal actor) {
        return root(actor).flatMap(root -> store.locked(state -> store.mode(false).then(store.stopAll())
            .then(store.audit("", actor.userId(), "DEVELOPER_MODE", "DISABLED", "loading revoked; managed cleanup requested"))
            .thenReturn(true))).then();
    }

    private Mono<Boolean> matches(AdminUserEntity root, String password) {
        return Mono.fromCallable(() -> password != null && password.length() <= 256
            && passwords.matches(password, root.getPasswordSalt(), root.getPasswordHash()))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Decision> reject(PluginStore.SecurityState state, AdminPrincipal actor, String action, Instant now) {
        int previous = state.failures() >= 5 && state.lockedUntil() != null && !state.lockedUntil().isAfter(now)
            ? 0 : state.failures();
        return store.failure(previous, now).then(store.audit("", actor.userId(), action, "DENIED",
                "verification failed; attempt=" + (previous + 1)))
            .thenReturn(new Decision(error(HttpStatus.FORBIDDEN, "PLUGIN_VERIFICATION_FAILED",
                "密码或验证码无效，或验证已过期；连续失败 5 次将锁定验证 15 分钟")));
    }

    private static ApiException blocked(PluginStore.SecurityState state, Instant now) {
        return state.lockedUntil() != null && state.lockedUntil().isAfter(now)
            ? error(HttpStatus.TOO_MANY_REQUESTS, "PLUGIN_VERIFICATION_LOCKED", "验证暂时锁定，请稍后重试") : null;
    }

    private static String identity(AdminUserEntity root) {
        return Hashing.sha256(root.getUserId() + "|" + root.getPasswordHash() + "|" + root.getPasswordSalt() + "|" + root.getEmail());
    }

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(1, at)) + "***" + email.substring(at);
    }

    static ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
    private record Decision(ApiException error) {}
}
