package tech.starryapi.payment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;

class InviteRebateServiceTest {

    @Test
    void delegatesByDurablePaymentOrderId() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        order.setOrderId("order-1");
        InviteRebatePersistenceService persistenceService = mock(InviteRebatePersistenceService.class);
        when(persistenceService.process("order-1")).thenReturn(Mono.just(1L));
        InviteRebateService service = new InviteRebateService(
            mock(R2dbcEntityTemplate.class),
            persistenceService
        );

        service.applyIfEligible(order).block();

        verify(persistenceService).process("order-1");
    }

    @Test
    void ignoresMissingOrderIdWithoutStartingPersistenceWork() {
        InviteRebatePersistenceService persistenceService = mock(InviteRebatePersistenceService.class);
        InviteRebateService service = new InviteRebateService(
            mock(R2dbcEntityTemplate.class),
            persistenceService
        );

        service.applyIfEligible(new PaymentOrderEntity()).block();

        verify(persistenceService, org.mockito.Mockito.never()).process(org.mockito.ArgumentMatchers.anyString());
    }
}
