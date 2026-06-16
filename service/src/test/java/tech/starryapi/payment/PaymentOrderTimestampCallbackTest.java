package tech.starryapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import reactor.core.publisher.Mono;

class PaymentOrderTimestampCallbackTest {

    private final PaymentOrderTimestampCallback callback = new PaymentOrderTimestampCallback();

    @Test
    void beforeConvertBackfillsCreatedAtForExistingOrderUpdate() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        order.setId(1L);

        PaymentOrderEntity result = beforeConvert(order);

        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    void beforeConvertPreservesCreatedAtAndRefreshesUpdatedAt() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant createdAt = Instant.parse("2026-06-09T00:00:00Z");
        Instant staleUpdatedAt = Instant.parse("2026-06-09T00:01:00Z");
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(staleUpdatedAt);

        PaymentOrderEntity result = beforeConvert(order);

        assertThat(result.getCreatedAt()).isEqualTo(createdAt);
        assertThat(result.getUpdatedAt()).isAfter(staleUpdatedAt);
    }

    private PaymentOrderEntity beforeConvert(PaymentOrderEntity order) {
        return Mono.from(callback.onBeforeConvert(order, SqlIdentifier.unquoted("payment_orders"))).block();
    }
}
