package tech.starryapi.admin;

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
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class InviteCodeService {

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_PART_LENGTH = 4;
    private static final int CODE_PARTS = 3;
    private static final int DEFAULT_BATCH_COUNT = 1;
    private static final int MAX_BATCH_COUNT = 100;

    private final InviteCodeRepository repository;
    private final R2dbcEntityTemplate template;
    private final SecureRandom random = new SecureRandom();

    public InviteCodeService(InviteCodeRepository repository, R2dbcEntityTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    public Flux<AdminDtos.InviteCodeResponse> list() {
        return repository.findAllByOrderByCreatedAtDesc().map(InviteCodeMapper::toResponse);
    }

    public Mono<AdminDtos.InviteCodeResponse> create(AdminDtos.CreateInviteCodeRequest request) {
        return saveNewInviteCode(request == null ? null : request.expiresAt());
    }

    public Mono<AdminDtos.CreateInviteCodesResponse> createBatch(AdminDtos.CreateInviteCodeRequest request) {
        Instant expiresAt = request == null ? null : request.expiresAt();
        int count = validateBatchCount(request == null ? null : request.count());
        return Flux.range(0, count)
            .concatMap(index -> saveNewInviteCode(expiresAt))
            .collectList()
            .map(codes -> new AdminDtos.CreateInviteCodesResponse(codes.size(), codes));
    }

    private Mono<AdminDtos.InviteCodeResponse> saveNewInviteCode(Instant expiresAt) {
        return Mono.defer(() -> repository.save(newInviteCode(expiresAt)))
            .retryWhen(Retry.max(5).filter(DuplicateKeyException.class::isInstance))
            .map(InviteCodeMapper::toResponse);
    }

    public Mono<AdminDtos.InviteCodeResponse> revoke(String codeId) {
        return repository.revokeByCodeId(clean(codeId))
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND", "Invite code not found")))
            .map(InviteCodeMapper::toResponse);
    }

    public Mono<Void> consume(String rawCode, String userId) {
        String code = normalizeCode(rawCode);
        if (code.isBlank()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVITE_CODE_REQUIRED", "Invite code is required"));
        }
        String sql = """
            UPDATE invite_codes
            SET used = TRUE,
                enabled = FALSE,
                used_by_user_id = :userId,
                used_at = CURRENT_TIMESTAMP(3)
            WHERE code = :code
              AND enabled = TRUE
              AND used = FALSE
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", clean(userId))
            .bind("code", code)
            .fetch()
            .rowsUpdated()
            .flatMap(rows -> rows > 0
                ? Mono.empty()
                : Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE", "Invite code is invalid, expired, or already used"))
            );
    }

    private InviteCodeEntity newInviteCode(Instant expiresAt) {
        InviteCodeEntity entity = new InviteCodeEntity();
        entity.setCodeId(Hashing.shortUuid());
        entity.setCode(generateCode());
        entity.setEnabled(true);
        entity.setUsed(false);
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    private int validateBatchCount(Integer value) {
        int count = value == null ? DEFAULT_BATCH_COUNT : value;
        if (count < 1 || count > MAX_BATCH_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITE_CODE_COUNT", "Invite code count must be between 1 and 100");
        }
        return count;
    }

    private String generateCode() {
        StringBuilder builder = new StringBuilder("INV");
        for (int part = 0; part < CODE_PARTS; part++) {
            builder.append('-');
            for (int index = 0; index < CODE_PART_LENGTH; index++) {
                builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
        }
        return builder.toString();
    }

    private String normalizeCode(String value) {
        return clean(value).toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
