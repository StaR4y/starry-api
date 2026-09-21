package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.availability.LivenessStateHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import reactor.core.publisher.Mono;

class DataManagementReadinessHealthIndicatorTest {

    @Test
    void lockedSystemStopsReadinessWithoutChangingLiveness() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));

        Status readiness = indicator(availability, securityService).health().block().getStatus();
        Status liveness = new LivenessStateHealthIndicator(availability).health().getStatus();

        assertThat(readiness).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(liveness).isEqualTo(Status.UP);
    }

    @Test
    void unlockedAcceptingSystemIsReady() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(false));

        assertThat(indicator(availability, securityService).health().block().getStatus())
            .isEqualTo(Status.UP);
    }

    @Test
    void applicationRefusingTrafficRemainsOutOfService() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);

        assertThat(indicator(availability, securityService).health().block().getStatus())
            .isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void lockSnapshotFailureFailsReadinessClosed() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.error(new IllegalStateException("state unavailable")));

        assertThat(indicator(availability, securityService).health().block().getStatus())
            .isEqualTo(Status.OUT_OF_SERVICE);
    }

    private DataManagementReadinessHealthIndicator indicator(
        ApplicationAvailability availability,
        DataManagementSecurityService securityService
    ) {
        return new DataManagementReadinessHealthIndicator(availability, securityService);
    }
}
