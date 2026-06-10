package xyz.star4y.api.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;
import xyz.star4y.api.payment.PaymentDtos.CreateRedemptionCodeRequest;
import xyz.star4y.api.payment.PaymentDtos.CreateRedemptionCodesResponse;
import xyz.star4y.api.payment.PaymentDtos.RedeemCodeRequest;
import xyz.star4y.api.payment.PaymentDtos.RedeemCodeResponse;
import xyz.star4y.api.payment.PaymentDtos.RedemptionCodeResponse;

@Service
public class RedemptionCodeService {

    private static final String USER_ROLE = "USER";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_PART_LENGTH = 4;
    private static final int CODE_PARTS = 3;
    private static final int DEFAULT_COUNT = 1;
    private static final int MAX_COUNT = 100;

    private final RedemptionCodeRepository repository;
    private final R2dbcEntityTemplate template;
    private final SecureRandom random = new SecureRandom();

    public RedemptionCodeService(RedemptionCodeRepository repository, R2dbcEntityTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    public Flux<RedemptionCodeResponse> list() {
        return repository.findTop200ByOrderByCreatedAtDesc().map(RedemptionCodeMapper::toResponse);
    }

    public Mono<CreateRedemptionCodesResponse> create(AdminPrincipal principal, CreateRedemptionCodeRequest request) {
        BigDecimal balance = validateBalance(request == null ? null : request.balance());
        int count = validateCount(request == null ? null : request.count());
        Instant expiresAt = request == null ? null : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE_EXPIRY", "Expiration time must be in the future"));
        }
        return Flux.range(0, count)
            .concatMap(index -> saveNewCode(principal, balance, expiresAt))
            .collectList()
            .map(codes -> new CreateRedemptionCodesResponse(codes.size(), codes));
    }

    public Mono<RedemptionCodeResponse> revoke(String codeId) {
        return repository.findByCodeId(clean(codeId))
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "REDEMPTION_CODE_NOT_FOUND", "Redemption code not found")))
            .flatMap(entity -> {
                entity.setEnabled(false);
                entity.setUpdatedAt(Instant.now());
                return repository.save(entity);
            })
            .map(RedemptionCodeMapper::toResponse);
    }

    public Mono<RedeemCodeResponse> redeem(AdminPrincipal principal, RedeemCodeRequest request) {
        if (!USER_ROLE.equalsIgnoreCase(principal.role())) {
            return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "USER_ROLE_REQUIRED", "Redemption codes are only available to ordinary users"));
        }
        String code = normalizeCode(request == null ? null : request.code());
        if (code.isBlank()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "REDEMPTION_CODE_REQUIRED", "Redemption code is required"));
        }
        Instant now = Instant.now();
        return redeemAtomically(principal.userId(), code, now)
            .flatMap(rows -> rows > 0
                ? redeemedResult(principal.userId(), code, now)
                : Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE", "Redemption code is invalid, expired, or already used"))
            );
    }

    private Mono<RedemptionCodeResponse> saveNewCode(AdminPrincipal principal, BigDecimal balance, Instant expiresAt) {
        return Mono.defer(() -> repository.save(newCode(principal, balance, expiresAt)))
            .retryWhen(Retry.max(5).filter(DuplicateKeyException.class::isInstance))
            .map(RedemptionCodeMapper::toResponse);
    }

    private RedemptionCodeEntity newCode(AdminPrincipal principal, BigDecimal balance, Instant expiresAt) {
        RedemptionCodeEntity entity = new RedemptionCodeEntity();
        Instant now = Instant.now();
        entity.setCodeId(Hashing.shortUuid());
        entity.setCode(generateCode());
        entity.setBalance(balance);
        entity.setEnabled(true);
        entity.setUsed(false);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedByUserId(clean(principal.userId()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private Mono<Long> redeemAtomically(String userId, String code, Instant now) {
        String sql = """
            UPDATE admin_users AS u
            JOIN redemption_codes AS r ON r.code = :code
            SET u.balance = u.balance + r.balance,
                u.updated_at = :now,
                r.enabled = FALSE,
                r.used = TRUE,
                r.used_by_user_id = u.user_id,
                r.used_by_username = u.username,
                r.used_at = :now,
                r.updated_at = :now
            WHERE u.user_id = :userId
              AND u.role = :userRole
              AND u.enabled = TRUE
              AND r.enabled = TRUE
              AND r.used = FALSE
              AND (r.expires_at IS NULL OR r.expires_at > :now)
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("code", code)
            .bind("now", now)
            .bind("userId", clean(userId))
            .bind("userRole", USER_ROLE)
            .fetch()
            .rowsUpdated();
    }

    private Mono<RedeemCodeResponse> redeemedResult(String userId, String code, Instant redeemedAt) {
        String sql = """
            SELECT r.code_id AS code_id,
                   r.balance AS redeemed_balance,
                   u.balance AS current_balance
            FROM admin_users AS u
            JOIN redemption_codes AS r ON r.used_by_user_id = u.user_id
            WHERE u.user_id = :userId
              AND r.code = :code
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", clean(userId))
            .bind("code", code)
            .map((row, metadata) -> new RedeemCodeResponse(
                row.get("code_id", String.class),
                row.get("redeemed_balance", BigDecimal.class),
                row.get("current_balance", BigDecimal.class),
                redeemedAt
            ))
            .one()
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REDEMPTION_CODE_RESULT_MISSING", "Redemption succeeded but result could not be loaded")));
    }

    private BigDecimal validateBalance(BigDecimal value) {
        BigDecimal balance = (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
        if (balance.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE_BALANCE", "Redemption code balance must be positive");
        }
        return balance;
    }

    private int validateCount(Integer value) {
        int count = value == null ? DEFAULT_COUNT : value;
        if (count < 1 || count > MAX_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE_COUNT", "Redemption code count must be between 1 and 100");
        }
        return count;
    }

    private String generateCode() {
        StringBuilder builder = new StringBuilder("BAL");
        for (int part = 0; part < CODE_PARTS; part++) {
            builder.append('-');
            for (int index = 0; index < CODE_PART_LENGTH; index++) {
                builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
        }
        return builder.toString();
    }

    private String normalizeCode(String value) {
        return clean(value).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
