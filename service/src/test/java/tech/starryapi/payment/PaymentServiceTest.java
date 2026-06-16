package tech.starryapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

    @Test
    void initializesCreatedAndUpdatedTimestampsForNewOrder() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant now = Instant.parse("2026-06-09T00:00:00Z");

        PaymentService.initializeTimestamps(order, now);

        assertThat(order.getCreatedAt()).isEqualTo(now);
        assertThat(order.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void touchPreservesExistingCreatedAtAndRefreshesUpdatedAt() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant created = Instant.parse("2026-06-09T00:00:00Z");
        Instant updated = Instant.parse("2026-06-09T00:10:00Z");
        order.setCreatedAt(created);

        PaymentService.touch(order, updated);

        assertThat(order.getCreatedAt()).isEqualTo(created);
        assertThat(order.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void touchBackfillsCreatedAtWhenRepositoryReturnedGeneratedDefaultsAsNull() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant now = Instant.parse("2026-06-09T00:00:00Z");

        PaymentService.touch(order, now);

        assertThat(order.getCreatedAt()).isEqualTo(now);
        assertThat(order.getUpdatedAt()).isEqualTo(now);
    }
}
