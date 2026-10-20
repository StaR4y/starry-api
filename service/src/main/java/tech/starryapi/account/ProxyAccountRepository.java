package tech.starryapi.account;

import java.util.Collection;
import java.time.Instant;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProxyAccountRepository extends ReactiveCrudRepository<ProxyAccountEntity, Long> {

    Mono<ProxyAccountEntity> findByAccountId(String accountId);

    Flux<ProxyAccountEntity> findAllByAccountIdIn(Collection<String> accountIds);

    @Query("SELECT * FROM proxy_accounts WHERE lower(email) = lower(:email) LIMIT 1")
    Mono<ProxyAccountEntity> findFirstByEmailOrderByCreatedAtAsc(@Param("email") String email);

    /**
     * Writes only the credential columns. Saving the whole entity after a read-then-write would also write back
     * stale counters and suspend state that changed in between.
     */
    @Modifying
    @Query("""
        UPDATE proxy_accounts
        SET access_token = :accessToken,
            refresh_token = :refreshToken,
            id_token = :idToken,
            client_id = :clientId,
            token_expires_at = COALESCE(:tokenExpiresAt, token_expires_at),
            updated_at = clock_timestamp()
        WHERE account_id = :accountId
        """)
    Mono<Integer> updateCredentials(
        @Param("accountId") String accountId,
        @Param("accessToken") String accessToken,
        @Param("refreshToken") String refreshToken,
        @Param("idToken") String idToken,
        @Param("clientId") String clientId,
        @Param("tokenExpiresAt") Instant tokenExpiresAt
    );

    Flux<ProxyAccountEntity> findAllByEnabledTrue();

    Flux<ProxyAccountEntity> findAllByEnabledFalseOrSuspendedAtNotNullOrQuotaExhaustedAtNotNull();

    Mono<Void> deleteByAccountId(String accountId);
}
