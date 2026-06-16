package tech.starryapi.model;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModelMappingRepository extends ReactiveCrudRepository<ModelMappingEntity, Long> {

    Flux<ModelMappingEntity> findAllByEnabledTrueOrderByPriorityAsc();

    Mono<ModelMappingEntity> findByMappingId(String mappingId);

    Mono<Void> deleteByMappingId(String mappingId);
}
