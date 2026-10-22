package tech.starryapi.admin;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("readinessStateHealthIndicator")
public class DataManagementReadinessHealthIndicator implements ReactiveHealthIndicator {

    private final ApplicationAvailability applicationAvailability;
    private final DataManagementSecurityService securityService;

    public DataManagementReadinessHealthIndicator(
        ApplicationAvailability applicationAvailability,
        DataManagementSecurityService securityService
    ) {
        this.applicationAvailability = applicationAvailability;
        this.securityService = securityService;
    }

    @Override
    public Mono<Health> health() {
        if (applicationAvailability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return Mono.just(Health.outOfService().build());
        }
        return securityService.isLocked()
            .map(locked -> Boolean.TRUE.equals(locked)
                ? Health.outOfService().build()
                : Health.up().build())
            .onErrorReturn(Health.outOfService().build());
    }
}
