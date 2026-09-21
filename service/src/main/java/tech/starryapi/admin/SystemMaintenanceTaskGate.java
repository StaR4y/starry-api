package tech.starryapi.admin;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SystemMaintenanceTaskGate {

    private static final SystemMaintenanceTaskGate ALWAYS_OPERATIONAL =
        new SystemMaintenanceTaskGate();

    private final DataManagementSecurityService securityService;

    @Autowired
    public SystemMaintenanceTaskGate(DataManagementSecurityService securityService) {
        this.securityService = Objects.requireNonNull(securityService, "securityService");
    }

    private SystemMaintenanceTaskGate() {
        this.securityService = null;
    }

    public static SystemMaintenanceTaskGate alwaysOperationalForDirectConstruction() {
        return ALWAYS_OPERATIONAL;
    }

    public <T> Mono<T> whenOperational(Supplier<? extends Mono<T>> task) {
        Objects.requireNonNull(task, "task");
        if (securityService == null) {
            return Mono.defer(task);
        }
        return Mono.defer(securityService::isLocked)
            .defaultIfEmpty(true)
            .onErrorReturn(true)
            .flatMap(locked -> Boolean.TRUE.equals(locked)
                ? Mono.empty()
                : Mono.defer(task));
    }
}
