package tech.starryapi.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.Hashing;
import tech.starryapi.payment.PaymentDtos.InviteRebateRecordResponse;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;

@Service
public class InviteRebateService {

    private static final String STATUS_COMPLETED = "COMPLETED";

    private final R2dbcEntityTemplate template;
    private final PaymentSettingsService settingsService;

    public InviteRebateService(R2dbcEntityTemplate template, PaymentSettingsService settingsService) {
        this.template = template;
        this.settingsService = settingsService;
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

    @Transactional
    public Mono<Void> applyIfEligible(PaymentOrderEntity order) {
        if (order == null
            || order.getId() == null
            || order.getOrderId() == null
            || order.getUserId() == null
            || order.getCreditedAt() == null
            || !STATUS_COMPLETED.equals(order.getStatus())) {
            return Mono.empty();
        }

        Instant now = Instant.now();
        return settingsService.config()
            .filter(config -> config.inviteRebateActiveAt(now))
            .flatMap(config -> inviteRelation(order.getUserId())
                .filter(relation -> !order.getUserId().equals(relation.inviterUserId()))
                .flatMap(relation -> rebateOrderNo(order)
                    .flatMap(orderNo -> {
                        if (orderNo < 1 || orderNo > config.inviteRebateMaxOrders()) {
                            return Mono.empty();
                        }
                        RebateDraft draft = draft(order, relation, config, orderNo);
                        return insertRecord(draft)
                            .flatMap(rows -> rows > 0 ? creditInviter(draft) : Mono.empty())
                            .onErrorResume(DuplicateKeyException.class, error -> Mono.empty());
                    })))
            .then();
    }

    private Mono<InviteRelation> inviteRelation(String invitedUserId) {
        String sql = """
            SELECT invited_by_user_id, invite_source_code
            FROM admin_users
            WHERE user_id = :invitedUserId
              AND invited_by_user_id IS NOT NULL
              AND invited_by_user_id <> ''
            LIMIT 1
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("invitedUserId", invitedUserId)
            .map((row, metadata) -> new InviteRelation(
                row.get("invite_source_code", String.class),
                row.get("invited_by_user_id", String.class)
            ))
            .one();
    }

    private Mono<Integer> rebateOrderNo(PaymentOrderEntity order) {
        String sql = """
            SELECT COUNT(*) AS total
            FROM payment_orders
            WHERE user_id = :userId
              AND status = :completedStatus
              AND credited_at IS NOT NULL
              AND (
                    credited_at < :creditedAt
                    OR (credited_at = :creditedAt AND id <= :id)
                  )
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", order.getUserId())
            .bind("completedStatus", STATUS_COMPLETED)
            .bind("creditedAt", order.getCreditedAt())
            .bind("id", order.getId())
            .map((row, metadata) -> number(row.get("total")))
            .one()
            .defaultIfEmpty(0);
    }

    private RebateDraft draft(PaymentOrderEntity order, InviteRelation relation, PaymentConfig config, int orderNo) {
        BigDecimal baseAmount = money(order.getAmount());
        BigDecimal rebateAmount = money(baseAmount.multiply(config.inviteRebateRate()));
        return new RebateDraft(
            Hashing.shortUuid(),
            relation.affiliateCode(),
            order.getOrderId(),
            relation.inviterUserId(),
            order.getUserId(),
            baseAmount,
            config.inviteRebateRate(),
            rebateAmount,
            orderNo
        );
    }

    private Mono<Long> insertRecord(RebateDraft draft) {
        String sql = """
            INSERT INTO invite_rebate_records (
                rebate_id, affiliate_code, payment_order_id, inviter_user_id, invited_user_id,
                base_amount, rebate_rate, rebate_amount, rebate_order_no
            ) VALUES (
                :rebateId, :affiliateCode, :paymentOrderId, :inviterUserId, :invitedUserId,
                :baseAmount, :rebateRate, :rebateAmount, :rebateOrderNo
            )
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("rebateId", draft.rebateId())
            .bind("affiliateCode", draft.affiliateCode())
            .bind("paymentOrderId", draft.paymentOrderId())
            .bind("inviterUserId", draft.inviterUserId())
            .bind("invitedUserId", draft.invitedUserId())
            .bind("baseAmount", draft.baseAmount())
            .bind("rebateRate", draft.rebateRate())
            .bind("rebateAmount", draft.rebateAmount())
            .bind("rebateOrderNo", draft.rebateOrderNo())
            .fetch()
            .rowsUpdated();
    }

    private Mono<Void> creditInviter(RebateDraft draft) {
        String sql = """
            UPDATE admin_users
            SET balance = balance + :rebateAmount,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = :inviterUserId
              AND enabled = TRUE
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("rebateAmount", draft.rebateAmount())
            .bind("inviterUserId", draft.inviterUserId())
            .fetch()
            .rowsUpdated()
            .then();
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record InviteRelation(String affiliateCode, String inviterUserId) {
    }

    private record RebateDraft(
        String rebateId,
        String affiliateCode,
        String paymentOrderId,
        String inviterUserId,
        String invitedUserId,
        BigDecimal baseAmount,
        BigDecimal rebateRate,
        BigDecimal rebateAmount,
        int rebateOrderNo
    ) {
    }
}
