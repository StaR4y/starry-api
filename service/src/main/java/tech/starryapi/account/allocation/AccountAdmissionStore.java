package tech.starryapi.account.allocation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface AccountAdmissionStore {

    Mono<AdmissionDecision> acquire(AdmissionRequest request);

    Mono<Void> markDispatched(UUID leaseId);

    Mono<Void> reconcile(AdmissionResult result);

    Mono<Void> releaseUnsent(UUID leaseId);

    Mono<Void> abandon(UUID leaseId, String reason);

    static AccountAdmissionStore permissive() {
        return new AccountAdmissionStore() {
            @Override
            public Mono<AdmissionDecision> acquire(AdmissionRequest request) {
                return Mono.just(new AdmissionDecision(
                    true,
                    UUID.randomUUID(),
                    null,
                    request.expectedStateVersion(),
                    Instant.now().plus(request.leaseTtl())
                ));
            }

            @Override
            public Mono<Void> markDispatched(UUID leaseId) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> reconcile(AdmissionResult result) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> releaseUnsent(UUID leaseId) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> abandon(UUID leaseId, String reason) {
                return Mono.empty();
            }
        };
    }

    record AdmissionRequest(
        String accountId,
        String requestId,
        Long expectedStateVersion,
        String sessionHash,
        boolean enforceSessionLimit,
        long reservedInputTokens,
        long reservedOutputTokens,
        Duration leaseTtl,
        String modelScope,
        String endpointScope
    ) {
        public AdmissionRequest(
            String accountId,
            String requestId,
            Long expectedStateVersion,
            String sessionHash,
            boolean enforceSessionLimit,
            long reservedInputTokens,
            long reservedOutputTokens,
            Duration leaseTtl
        ) {
            this(
                accountId,
                requestId,
                expectedStateVersion,
                sessionHash,
                enforceSessionLimit,
                reservedInputTokens,
                reservedOutputTokens,
                leaseTtl,
                null,
                null
            );
        }

        public AdmissionRequest {
            reservedInputTokens = Math.max(0, reservedInputTokens);
            reservedOutputTokens = Math.max(0, reservedOutputTokens);
            leaseTtl = leaseTtl == null ? Duration.ofMinutes(10) : leaseTtl;
            modelScope = clean(modelScope);
            endpointScope = clean(endpointScope);
        }

        private static String clean(String value) {
            if (value == null) {
                return null;
            }
            String cleaned = value.trim();
            return cleaned.isEmpty() ? null : cleaned;
        }
    }

    record AdmissionDecision(
        boolean accepted,
        UUID leaseId,
        String rejectionReason,
        Long stateVersion,
        Instant expiresAt
    ) {
    }

    record AdmissionResult(
        UUID leaseId,
        boolean success,
        long actualInputTokens,
        long actualOutputTokens,
        long latencyMs,
        Integer statusCode,
        AccountFailureScope failureScope,
        Duration cooldown,
        boolean permanentCredentialFailure,
        Map<String, Object> evidence
    ) {
        public AdmissionResult {
            actualInputTokens = Math.max(0, actualInputTokens);
            actualOutputTokens = Math.max(0, actualOutputTokens);
            latencyMs = Math.max(0, latencyMs);
            failureScope = failureScope == null ? AccountFailureScope.UNKNOWN : failureScope;
            cooldown = cooldown == null ? Duration.ofSeconds(45) : cooldown;
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }
}
