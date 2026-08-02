package tech.starryapi.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.Hashing;

@Service
public class InviteRebatePersistenceService {

    private static final int REBATE_RANK_AUDIT_LIMIT = 101;

    private final R2dbcEntityTemplate template;

    public InviteRebatePersistenceService(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Transactional
    public Mono<Long> process(String paymentOrderId) {
        return lockPendingPayment(paymentOrderId)
            .flatMap(payment -> {
                validateSnapshot(payment);
                Instant paymentEffectiveAt = paymentEffectiveAt(payment);
                return lockInviteContext(payment.userId())
                    .defaultIfEmpty(InviteContext.NONE)
                    .flatMap(context -> rankAndRefreshRebateRecords(payment, paymentEffectiveAt)
                        .flatMap(paymentOrderNo -> applyIfEligible(
                            payment,
                            context,
                            paymentEffectiveAt,
                            paymentOrderNo
                        )))
                    .then(markProcessed(payment.orderId()));
            })
            .defaultIfEmpty(0L);
    }

    private Mono<PendingRebatePayment> lockPendingPayment(String paymentOrderId) {
        String sql = """
            SELECT id, order_id, user_id, amount, paid_at, completed_at, credited_at,
                   rebate_snapshot_at, rebate_enabled, rebate_rate, rebate_max_orders,
                   rebate_campaign_start_at, rebate_campaign_end_at
            FROM payment_orders
            WHERE order_id = :paymentOrderId
              AND status = 'COMPLETED'
              AND credited_at IS NOT NULL
              AND rebate_processed_at IS NULL
            FOR UPDATE
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("paymentOrderId", paymentOrderId)
            .map((row, metadata) -> new PendingRebatePayment(
                row.get("id", Long.class),
                row.get("order_id", String.class),
                row.get("user_id", String.class),
                row.get("amount", BigDecimal.class),
                row.get("paid_at", Instant.class),
                row.get("completed_at", Instant.class),
                row.get("credited_at", Instant.class),
                row.get("rebate_snapshot_at", Instant.class),
                row.get("rebate_enabled", Boolean.class),
                row.get("rebate_rate", BigDecimal.class),
                row.get("rebate_max_orders", Integer.class),
                row.get("rebate_campaign_start_at", Instant.class),
                row.get("rebate_campaign_end_at", Instant.class)
            ))
            .one();
    }

    private Mono<Long> applyIfEligible(
        PendingRebatePayment payment,
        InviteContext context,
        Instant paymentEffectiveAt,
        int paymentOrderNo
    ) {
        if (!rebateActiveAt(payment, paymentEffectiveAt)) {
            return Mono.just(0L);
        }
        if (!context.eligibleFor(payment.userId())) {
            return Mono.just(0L);
        }
        if (paymentOrderNo < 1 || paymentOrderNo > payment.rebateMaxOrders()) {
            return Mono.just(0L);
        }

        BigDecimal baseAmount = money(payment.amount());
        BigDecimal rebateAmount = money(baseAmount.multiply(payment.rebateRate()));
        if (baseAmount.signum() <= 0 || rebateAmount.signum() <= 0) {
            return Mono.just(0L);
        }
        return applyOnce(
            payment,
            context,
            paymentEffectiveAt,
            paymentOrderNo,
            baseAmount,
            rebateAmount
        );
    }

    private void validateSnapshot(PendingRebatePayment payment) {
        if (payment.snapshotAt() == null
            || payment.rebateEnabled() == null
            || payment.rebateRate() == null
            || payment.rebateMaxOrders() == null) {
            throw new IllegalStateException(
                "Payment order has no durable rebate configuration snapshot: " + payment.orderId()
            );
        }
    }

    private boolean rebateActiveAt(PendingRebatePayment payment, Instant paymentEffectiveAt) {
        if (!Boolean.TRUE.equals(payment.rebateEnabled())) {
            return false;
        }
        if (payment.campaignStartAt() != null && paymentEffectiveAt.isBefore(payment.campaignStartAt())) {
            return false;
        }
        return payment.campaignEndAt() == null || paymentEffectiveAt.isBefore(payment.campaignEndAt());
    }

    private Instant paymentEffectiveAt(PendingRebatePayment payment) {
        Instant effectiveAt = payment.paidAt() != null
            ? payment.paidAt()
            : payment.completedAt() != null ? payment.completedAt() : payment.creditedAt();
        if (effectiveAt == null) {
            throw new IllegalStateException(
                "Completed payment order has no effective payment time: " + payment.orderId()
            );
        }
        return effectiveAt;
    }

    private Mono<InviteContext> lockInviteContext(String invitedUserId) {
        String sql = """
            SELECT invited_by_user_id, invite_source_code
            FROM admin_users
            WHERE user_id = :invitedUserId
            FOR UPDATE
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("invitedUserId", invitedUserId)
            .map((row, metadata) -> new InviteContext(
                row.get("invite_source_code", String.class),
                row.get("invited_by_user_id", String.class)
            ))
            .one();
    }

    private Mono<Integer> rankAndRefreshRebateRecords(
        PendingRebatePayment payment,
        Instant paymentEffectiveAt
    ) {
        String sql = """
            WITH ranked_existing AS MATERIALIZED (
                SELECT rebate.id,
                       (
                           SELECT COUNT(*)::integer
                           FROM (
                               SELECT candidate.id
                               FROM payment_orders AS candidate
                               WHERE candidate.user_id = :invitedUserId
                                 AND candidate.status = 'COMPLETED'
                                 AND candidate.credited_at IS NOT NULL
                                 AND (
                                     COALESCE(candidate.paid_at, candidate.completed_at, candidate.credited_at)
                                         < rebate.payment_effective_at
                                     OR (
                                         COALESCE(candidate.paid_at, candidate.completed_at, candidate.credited_at)
                                             = rebate.payment_effective_at
                                         AND candidate.id <= source.id
                                     )
                                 )
                               ORDER BY COALESCE(
                                   candidate.paid_at,
                                   candidate.completed_at,
                                   candidate.credited_at
                               ), candidate.id
                               LIMIT :rankAuditLimit
                           ) AS ranked_payments
                       ) AS payment_order_no
                FROM invite_rebate_records AS rebate
                INNER JOIN payment_orders AS source
                    ON source.order_id = rebate.payment_order_id
                WHERE rebate.invited_user_id = :invitedUserId
            ), reranked AS (
                UPDATE invite_rebate_records AS rebate
                SET payment_order_no = ranked_existing.payment_order_no
                FROM ranked_existing
                WHERE rebate.id = ranked_existing.id
                  AND rebate.payment_order_no <> ranked_existing.payment_order_no
                RETURNING rebate.id
            ), preceding AS MATERIALIZED (
                SELECT p.id
                FROM payment_orders AS p
                WHERE p.user_id = :invitedUserId
                  AND p.status = 'COMPLETED'
                  AND p.credited_at IS NOT NULL
                  AND (
                      COALESCE(p.paid_at, p.completed_at, p.credited_at) < :paymentEffectiveAt
                      OR (
                          COALESCE(p.paid_at, p.completed_at, p.credited_at) = :paymentEffectiveAt
                          AND p.id < :paymentId
                      )
                  )
                ORDER BY COALESCE(p.paid_at, p.completed_at, p.credited_at), p.id
                LIMIT :eligibilityLimit
            )
            SELECT COUNT(*)::integer + 1 AS payment_order_no
            FROM preceding
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("invitedUserId", payment.userId())
            .bind("paymentEffectiveAt", paymentEffectiveAt)
            .bind("paymentId", payment.id())
            .bind("rankAuditLimit", REBATE_RANK_AUDIT_LIMIT)
            .bind("eligibilityLimit", payment.rebateMaxOrders())
            .map((row, metadata) -> row.get("payment_order_no", Integer.class))
            .one();
    }

    private Mono<Long> applyOnce(
        PendingRebatePayment payment,
        InviteContext context,
        Instant paymentEffectiveAt,
        int paymentOrderNo,
        BigDecimal baseAmount,
        BigDecimal rebateAmount
    ) {
        String sql = """
            WITH issued AS MATERIALIZED (
                SELECT COUNT(*)::integer AS issued_count
                FROM invite_rebate_records
                WHERE invited_user_id = :invitedUserId
            ), candidate AS MATERIALIZED (
                SELECT issued.issued_count + 1 AS rebate_order_no,
                       :paymentOrderNo AS payment_order_no,
                       inviter.balance AS balance_before,
                       inviter.balance + :rebateAmount AS balance_after
                FROM issued
                INNER JOIN admin_users AS inviter
                    ON inviter.user_id = :inviterUserId
                   AND inviter.enabled = TRUE
                FOR UPDATE OF inviter
            ), inserted AS (
                INSERT INTO invite_rebate_records (
                    rebate_id, affiliate_code, payment_order_id, inviter_user_id, invited_user_id,
                    base_amount, rebate_rate, rebate_amount, rebate_order_no, payment_order_no,
                    rebate_max_orders, payment_effective_at, campaign_start_at, campaign_end_at
                )
                SELECT :rebateId, :affiliateCode, :paymentOrderId, :inviterUserId, :invitedUserId,
                       :baseAmount, :rebateRate, :rebateAmount, candidate.rebate_order_no,
                       candidate.payment_order_no, :maxOrders, :paymentEffectiveAt,
                       :campaignStartAt, :campaignEndAt
                FROM candidate
                ON CONFLICT (payment_order_id) DO NOTHING
                RETURNING payment_order_id, inviter_user_id, rebate_rate, rebate_amount,
                          rebate_order_no, payment_order_no, rebate_max_orders,
                          payment_effective_at, campaign_start_at, campaign_end_at
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       inserted.inviter_user_id,
                       'INVITE_REBATE',
                       inserted.rebate_amount,
                       candidate.balance_before,
                       candidate.balance_after,
                       'PAYMENT_ORDER',
                       inserted.payment_order_id,
                       'invite-rebate:' || inserted.payment_order_id,
                       jsonb_strip_nulls(jsonb_build_object(
                           'rebate_rate', inserted.rebate_rate,
                           'rebate_order_no', inserted.rebate_order_no,
                           'payment_order_no', inserted.payment_order_no,
                           'rebate_max_orders', inserted.rebate_max_orders,
                           'payment_effective_at', inserted.payment_effective_at,
                           'campaign_start_at', inserted.campaign_start_at,
                           'campaign_end_at', inserted.campaign_end_at
                       ))
                FROM inserted
                INNER JOIN candidate ON TRUE
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id, balance_after
            )
            UPDATE admin_users AS inviter
            SET balance = ledger_entry.balance_after,
                updated_at = CURRENT_TIMESTAMP
            FROM ledger_entry
            WHERE inviter.user_id = ledger_entry.user_id
              AND inviter.enabled = TRUE
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("transactionId", Hashing.shortUuid())
            .bind("paymentEffectiveAt", paymentEffectiveAt)
            .bind("paymentOrderNo", paymentOrderNo)
            .bind("invitedUserId", payment.userId())
            .bind("inviterUserId", context.inviterUserId())
            .bind("affiliateCode", context.affiliateCode())
            .bind("maxOrders", payment.rebateMaxOrders())
            .bind("rebateId", Hashing.shortUuid())
            .bind("paymentOrderId", payment.orderId())
            .bind("baseAmount", baseAmount)
            .bind("rebateRate", payment.rebateRate())
            .bind("rebateAmount", rebateAmount);
        spec = bindNullable(spec, "campaignStartAt", payment.campaignStartAt());
        spec = bindNullable(spec, "campaignEndAt", payment.campaignEndAt());
        return spec.fetch().rowsUpdated();
    }

    private Mono<Long> markProcessed(String paymentOrderId) {
        String sql = """
            UPDATE payment_orders
            SET rebate_processed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE order_id = :paymentOrderId
              AND status = 'COMPLETED'
              AND credited_at IS NOT NULL
              AND rebate_snapshot_at IS NOT NULL
              AND rebate_processed_at IS NULL
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("paymentOrderId", paymentOrderId)
            .fetch()
            .rowsUpdated();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Instant value) {
        return value == null ? spec.bindNull(name, Instant.class) : spec.bind(name, value);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private record InviteContext(String affiliateCode, String inviterUserId) {

        private static final InviteContext NONE = new InviteContext(null, null);

        boolean eligibleFor(String invitedUserId) {
            return inviterUserId != null
                && !inviterUserId.isBlank()
                && !inviterUserId.equals(invitedUserId)
                && affiliateCode != null
                && !affiliateCode.isBlank();
        }
    }

    private record PendingRebatePayment(
        Long id,
        String orderId,
        String userId,
        BigDecimal amount,
        Instant paidAt,
        Instant completedAt,
        Instant creditedAt,
        Instant snapshotAt,
        Boolean rebateEnabled,
        BigDecimal rebateRate,
        Integer rebateMaxOrders,
        Instant campaignStartAt,
        Instant campaignEndAt
    ) {
    }
}
