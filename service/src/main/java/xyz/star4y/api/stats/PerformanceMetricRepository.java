package xyz.star4y.api.stats;

import java.time.Instant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PerformanceMetricRepository extends ReactiveCrudRepository<PerformanceMetricEntity, Long> {

    Flux<PerformanceMetricEntity> findTop120ByOrderBySampledAtDesc();

    Mono<Void> deleteBySampledAtBefore(Instant sampledAt);
}
