package tech.starryapi.admin;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface DataManagementSecurityIncidentHandler {

    Mono<Void> handle(DataManagementSecurityIncident incident);
}
