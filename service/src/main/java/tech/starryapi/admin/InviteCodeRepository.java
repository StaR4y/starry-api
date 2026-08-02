package tech.starryapi.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InviteCodeRepository extends ReactiveCrudRepository<InviteCodeEntity, Long> {

    Flux<InviteCodeEntity> findAllByOrderByCreatedAtDesc();

    Mono<InviteCodeEntity> findByCodeId(String codeId);

    @Query("""
        UPDATE invite_codes
        SET enabled = FALSE
        WHERE code_id = :codeId
        RETURNING *
        """)
    Mono<InviteCodeEntity> revokeByCodeId(String codeId);
}
