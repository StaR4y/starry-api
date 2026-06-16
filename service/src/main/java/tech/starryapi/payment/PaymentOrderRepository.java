package tech.starryapi.payment;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PaymentOrderRepository extends ReactiveCrudRepository<PaymentOrderEntity, Long> {

    Mono<PaymentOrderEntity> findByOrderId(String orderId);

    Mono<PaymentOrderEntity> findByOutTradeNo(String outTradeNo);

    Flux<PaymentOrderEntity> findTop50ByUserIdOrderByCreatedAtDesc(String userId);

    Flux<PaymentOrderEntity> findTop100ByOrderByCreatedAtDesc();
}
