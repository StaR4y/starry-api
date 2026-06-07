package xyz.star4y.api.stats;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface RequestLogRepository extends ReactiveCrudRepository<RequestLogEntity, Long> {

    Flux<RequestLogEntity> findTop100ByOrderByCreatedAtDesc();
}
