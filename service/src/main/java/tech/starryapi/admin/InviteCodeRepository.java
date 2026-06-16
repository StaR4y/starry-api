package tech.starryapi.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InviteCodeRepository extends ReactiveCrudRepository<InviteCodeEntity, Long> {

    Flux<InviteCodeEntity> findAllByOrderByCreatedAtDesc();

    Mono<InviteCodeEntity> findByCodeId(String codeId);
}
