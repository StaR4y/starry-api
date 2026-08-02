package tech.starryapi.account;

import java.util.Collection;
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

    Flux<ProxyAccountEntity> findAllByEnabledTrue();

    Flux<ProxyAccountEntity> findAllByEnabledFalseOrSuspendedAtNotNullOrQuotaExhaustedAtNotNull();

    Mono<Void> deleteByAccountId(String accountId);
}
