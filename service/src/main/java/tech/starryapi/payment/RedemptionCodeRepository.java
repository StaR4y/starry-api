package tech.starryapi.payment;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RedemptionCodeRepository extends ReactiveCrudRepository<RedemptionCodeEntity, Long> {

    Mono<RedemptionCodeEntity> findByCodeId(String codeId);

    Flux<RedemptionCodeEntity> findTop200ByOrderByCreatedAtDesc();
}
