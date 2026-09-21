package tech.starryapi.platform.openai;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class OpenAiResponseOwnerStore {

    private static final Duration OWNER_TTL = Duration.ofHours(6);

    private final DatabaseClient databaseClient;

    public OpenAiResponseOwnerStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<String> findOwner(String responseId) {
        if (responseId == null || responseId.isBlank()) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                SELECT account_id
                FROM openai_response_owners
                WHERE response_id = :responseId AND expires_at > CURRENT_TIMESTAMP
                """)
            .bind("responseId", responseId)
            .map((row, metadata) -> row.get("account_id", String.class))
            .one();
    }

    public Mono<Void> remember(String responseId, String accountId) {
        if (responseId == null || responseId.isBlank() || accountId == null || accountId.isBlank()) {
            return Mono.empty();
        }
        Instant expiresAt = Instant.now().plus(OWNER_TTL);
        return databaseClient.sql("""
                INSERT INTO openai_response_owners (response_id, account_id, expires_at)
                VALUES (:responseId, :accountId, :expiresAt)
                ON CONFLICT (response_id) DO UPDATE
                SET account_id = EXCLUDED.account_id,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = clock_timestamp()
                """)
            .bind("responseId", responseId)
            .bind("accountId", accountId)
            .bind("expiresAt", expiresAt)
            .fetch()
            .rowsUpdated()
            .then();
    }

    static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        return null;
    }
}
