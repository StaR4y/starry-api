package tech.starryapi.payment;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.payment.PaymentDtos.InviteRebateRecordResponse;

@Service
public class InviteRebateService {

    private final R2dbcEntityTemplate template;
    private final InviteRebatePersistenceService persistenceService;

    public InviteRebateService(
        R2dbcEntityTemplate template,
        InviteRebatePersistenceService persistenceService
    ) {
        this.template = template;
        this.persistenceService = persistenceService;
    }

    public Flux<InviteRebateRecordResponse> listRecent() {
        String sql = """
            SELECT rebate_id, affiliate_code, payment_order_id, inviter_user_id, invited_user_id,
                   base_amount, rebate_rate, rebate_amount, rebate_order_no, created_at
            FROM invite_rebate_records
            ORDER BY created_at DESC
            LIMIT 200
            """;
        return template.getDatabaseClient().sql(sql)
            .map((row, metadata) -> new InviteRebateRecordResponse(
                row.get("rebate_id", String.class),
                row.get("affiliate_code", String.class),
                row.get("payment_order_id", String.class),
                row.get("inviter_user_id", String.class),
                row.get("invited_user_id", String.class),
                row.get("base_amount", BigDecimal.class),
                row.get("rebate_rate", BigDecimal.class),
                row.get("rebate_amount", BigDecimal.class),
                number(row.get("rebate_order_no")),
                row.get("created_at", Instant.class)
            ))
            .all();
    }

    public Mono<Void> applyIfEligible(PaymentOrderEntity order) {
        if (order == null
            || order.getOrderId() == null
            || order.getOrderId().isBlank()) {
            return Mono.empty();
        }
        return processPending(order.getOrderId()).then();
    }

    public Mono<Long> processPending(String paymentOrderId) {
        if (paymentOrderId == null || paymentOrderId.isBlank()) {
            return Mono.just(0L);
        }
        return persistenceService.process(paymentOrderId);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
