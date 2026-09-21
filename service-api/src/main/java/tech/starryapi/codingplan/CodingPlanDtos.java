package tech.starryapi.codingplan;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CodingPlanDtos {

    private CodingPlanDtos() {
    }

    public record CodingPlanResponse(
        String planId,
        Integer version,
        String name,
        String description,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal priceCredits,
        Integer durationDays,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal quotaCredits,
        List<String> allowedProviders,
        Integer sortOrder,
        Boolean enabled,
        Instant createdAt,
        Instant updatedAt
    ) {
        public CodingPlanResponse {
            allowedProviders = allowedProviders == null ? List.of() : List.copyOf(allowedProviders);
        }
    }

    public record CreateCodingPlanRequest(
        @NotBlank String name,
        String description,
        @NotNull @DecimalMin(value = "0") BigDecimal priceCredits,
        @NotNull @Min(1) @Max(3650) Integer durationDays,
        @NotNull @DecimalMin(value = "0.000001") BigDecimal quotaCredits,
        List<String> allowedProviders,
        Integer sortOrder
    ) {
    }

    public record UpdateCodingPlanRequest(
        String name,
        String description,
        BigDecimal priceCredits,
        Integer durationDays,
        BigDecimal quotaCredits,
        List<String> allowedProviders,
        Integer sortOrder,
        Boolean enabled
    ) {
    }

    public record CodingPlanSubscriptionResponse(
        String subscriptionId,
        String userId,
        String username,
        String planId,
        String planName,
        Integer planVersion,
        String status,
        Instant startsAt,
        Instant endsAt,
        BigDecimal quotaCredits,
        BigDecimal usedCredits,
        BigDecimal remainingCredits,
        BigDecimal reservedCredits,
        BigDecimal availableCredits,
        List<String> allowedProviders,
        String source,
        String sourceRefId,
        Instant createdAt,
        Instant updatedAt
    ) {
        public CodingPlanSubscriptionResponse {
            allowedProviders = allowedProviders == null ? List.of() : List.copyOf(allowedProviders);
        }
    }

    public record PurchaseCodingPlanRequest(
        @NotNull @Min(1) Integer version
    ) {
    }

    public record PurchaseCodingPlanResponse(
        CodingPlanSubscriptionResponse subscription,
        BigDecimal balance
    ) {
    }

    public record AdminGrantSubscriptionRequest(
        @NotBlank String userId,
        @NotBlank String planId
    ) {
    }

    public record AdminUpdateSubscriptionRequest(
        @NotBlank String status,
        @NotBlank @jakarta.validation.constraints.Size(max = 512) String reason
    ) {
    }

    public record SubscriptionPage(List<CodingPlanSubscriptionResponse> items, long total, int page, int pageSize) {}
    public record SubscriptionSummary(String availableCredits, String reservedCredits, Instant nextExpiry, long activeCount) {}
    public record SubscriptionLedgerEntry(long id, String entryType, BigDecimal amount, BigDecimal reservedDelta,
        String referenceId, String actorId, String reason, Instant createdAt) {}
}
