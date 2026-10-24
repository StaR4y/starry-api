package tech.starryapi.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** The routing limits a new account starts with, per account category. */
public final class AllocationDefaultsDtos {
    private AllocationDefaultsDtos() {}

    /**
     * Same ranges as the per-account allocation policy. 0 means unlimited for {@code safeConcurrency} and
     * {@code maxSessions}, off for {@code safeRpm} and automatic for {@code cooldownSeconds}; {@code burstCapacity} is
     * 0 exactly when {@code safeRpm} is 0. Session limits and TLS profiles exist only for Anthropic accounts.
     */
    public record Policy(
        @NotNull @Min(0) @Max(10000) Integer safeConcurrency,
        @NotNull @Min(0) @Max(1000000) Integer safeRpm,
        @NotNull @Min(0) @Max(100000) Integer burstCapacity,
        @NotNull @Min(0) @Max(100000) Integer maxSessions,
        @NotNull @Min(60) @Max(86400) Integer sessionIdleSeconds,
        @NotNull @Min(0) @Max(86400) Integer cooldownSeconds,
        @NotNull @Min(1) @Max(1000000000) Long maxInflightTokens,
        @NotNull @Min(1) @Max(10000) Integer allocationPriority,
        @NotNull @Pattern(regexp = "|chrome136|safari18_4") String tlsProfile
    ) {}

    /**
     * {@code builtIn} is what applies when nothing is configured, {@code configured} the administrator's values (null
     * when unset) and {@code effective} what a new account of the category gets now. {@code anthropicSettings} says
     * whether {@code maxSessions} and {@code tlsProfile} can apply to the category at all.
     */
    public record CategoryDefaults(String category, boolean anthropicSettings, Policy builtIn, Policy configured, Policy effective) {}

    public record Defaults(List<CategoryDefaults> categories) {}

    /** Replaces the configuration: a category that is missing or null goes back to its built-in values. */
    public record UpdateDefaults(Map<String, @Valid Policy> categories) {}

    public record ResolveRequest(
        @Size(max = 128) String provider,
        @Size(max = 64) String authMethod,
        @Size(max = 2048) String baseUrl
    ) {}

    /** The category a new account with these properties falls into and the policy it would start with. */
    public record ResolveResponse(String category, Policy policy) {}
}
