package tech.starryapi.admin;

import java.time.Instant;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface RegistrationEmailTokenRepository extends Repository<RegistrationEmailTokenEntity, String> {

    @Query("""
        WITH previous AS MATERIALIZED (
            SELECT token_hash, expires_at, next_send_at
            FROM registration_email_tokens
            WHERE email = :email
            FOR UPDATE
        ), updated AS (
            UPDATE registration_email_tokens AS current_token
            SET token_hash = :tokenHash,
                expires_at = :expiresAt,
                next_send_at = :nextSendAt,
                updated_at = CURRENT_TIMESTAMP
            WHERE current_token.email = :email
              AND EXISTS (SELECT 1 FROM previous)
              AND current_token.next_send_at <= CURRENT_TIMESTAMP
            RETURNING current_token.email
        ), inserted AS (
            INSERT INTO registration_email_tokens (
                email, token_hash, expires_at, next_send_at
            )
            SELECT :email, :tokenHash, :expiresAt, :nextSendAt
            WHERE NOT EXISTS (SELECT 1 FROM previous)
            ON CONFLICT (email) DO NOTHING
            RETURNING email
        ), reserved AS (
            SELECT email FROM updated
            UNION ALL
            SELECT email FROM inserted
        )
        SELECT reserved.email,
               :tokenHash AS reserved_token_hash,
               previous.token_hash AS previous_token_hash,
               previous.expires_at AS previous_expires_at,
               previous.next_send_at AS previous_next_send_at
        FROM reserved
        LEFT JOIN previous ON TRUE
        """)
    Mono<Reservation> reserve(
        @Param("email") String email,
        @Param("tokenHash") String tokenHash,
        @Param("expiresAt") Instant expiresAt,
        @Param("nextSendAt") Instant nextSendAt
    );

    @Query("""
        SELECT GREATEST(
            1,
            CEIL(EXTRACT(EPOCH FROM (next_send_at - CURRENT_TIMESTAMP)))::BIGINT
        )
        FROM registration_email_tokens
        WHERE email = :email
          AND next_send_at > CURRENT_TIMESTAMP
        """)
    Mono<Long> cooldownSeconds(@Param("email") String email);

    @Query("""
        WITH consumed AS MATERIALIZED (
            DELETE FROM registration_email_tokens
            WHERE email = :email
              AND token_hash = :tokenHash
              AND expires_at > CURRENT_TIMESTAMP
            RETURNING 1
        )
        SELECT EXISTS (SELECT 1 FROM consumed)
        """)
    Mono<Boolean> consume(
        @Param("email") String email,
        @Param("tokenHash") String tokenHash
    );

    @Modifying
    @Query("""
        DELETE FROM registration_email_tokens
        WHERE email = :email
          AND token_hash = :tokenHash
        """)
    Mono<Integer> release(
        @Param("email") String email,
        @Param("tokenHash") String tokenHash
    );

    @Modifying
    @Query("""
        UPDATE registration_email_tokens
        SET token_hash = :previousTokenHash,
            expires_at = :previousExpiresAt,
            next_send_at = :previousNextSendAt,
            updated_at = CURRENT_TIMESTAMP
        WHERE email = :email
          AND token_hash = :reservedTokenHash
          AND :previousExpiresAt > CURRENT_TIMESTAMP
        """)
    Mono<Integer> restoreIfUsable(
        @Param("email") String email,
        @Param("reservedTokenHash") String reservedTokenHash,
        @Param("previousTokenHash") String previousTokenHash,
        @Param("previousExpiresAt") Instant previousExpiresAt,
        @Param("previousNextSendAt") Instant previousNextSendAt
    );

    @Modifying
    @Query("""
        WITH expired AS MATERIALIZED (
            SELECT email
            FROM registration_email_tokens
            WHERE expires_at <= CURRENT_TIMESTAMP
            ORDER BY expires_at, email
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        DELETE FROM registration_email_tokens AS token
        USING expired
        WHERE token.email = expired.email
        """)
    Mono<Integer> pruneExpired(@Param("batchSize") int batchSize);

    record Reservation(
        String email,
        String reservedTokenHash,
        String previousTokenHash,
        Instant previousExpiresAt,
        Instant previousNextSendAt
    ) {
        boolean replacesPreviousToken() {
            return previousTokenHash != null;
        }
    }
}
