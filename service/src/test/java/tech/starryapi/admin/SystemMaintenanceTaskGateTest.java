package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SystemMaintenanceTaskGateTest {

    @Test
    void subscribesToTaskOnlyWhenSystemIsOperational() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(false));
        AtomicInteger subscriptions = new AtomicInteger();

        Integer result = new SystemMaintenanceTaskGate(securityService)
            .whenOperational(() -> Mono.fromSupplier(subscriptions::incrementAndGet))
            .block();

        assertThat(result).isEqualTo(1);
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void skipsTaskWhenMaintenanceIsLocked() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        AtomicInteger subscriptions = new AtomicInteger();

        assertThat(new SystemMaintenanceTaskGate(securityService)
            .whenOperational(() -> Mono.fromSupplier(subscriptions::incrementAndGet))
            .blockOptional()).isEmpty();
        assertThat(subscriptions).hasValue(0);
    }

    @Test
    void failsClosedWhenLockStateCannotBeRead() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        AtomicInteger subscriptions = new AtomicInteger();

        assertThat(new SystemMaintenanceTaskGate(securityService)
            .whenOperational(() -> Mono.fromSupplier(subscriptions::incrementAndGet))
            .blockOptional()).isEmpty();
        assertThat(subscriptions).hasValue(0);
    }

    @Test
    void preservesTaskFailuresForTheOwningScheduler() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(false));

        assertThatThrownBy(() -> new SystemMaintenanceTaskGate(securityService)
            .whenOperational(() -> Mono.error(new IllegalArgumentException("task failed")))
            .block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("task failed");
    }
}
