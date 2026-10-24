package tech.starryapi.egress;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProxyChannelDtos {
    private ProxyChannelDtos() {}

    /**
     * {@code route} is DIRECT_PROXY for a plain HTTP CONNECT or SOCKS5 node that this application connects to itself (it
     * works without Mihomo and is ACTIVE whenever it is enabled, present and without a configuration issue) or MIHOMO
     * for every other protocol (it needs the Mihomo component). {@code lastTest*} is the most recent exit probe of this node ({@code lastTestResult} is SUCCESS or FAILED, null
     * when never tested); {@code configIssue} is why a node cannot be used, {@code sourcePresent} is false once the
     * subscription no longer lists it.
     */
    public record Channel(UUID channelId, UUID subscriptionId, String subscriptionName, String name, String protocol,
                          String route, boolean enabled, String runtimeStatus, String runtimeError,
                          long boundAccounts, Instant updatedAt, String configIssue, boolean sourcePresent,
                          Instant lastTestedAt, String lastTestResult, String lastTestExitIp, Long lastTestLatencyMs,
                          String lastTestErrorCode, String lastTestError) {}
    public record Subscription(UUID subscriptionId, String name, String status,
                               Instant lastSyncedAt, String lastError, long nodeCount, boolean refreshable) {}
    /**
     * State of the Mihomo component as seen from this instance. {@code controllerReachable} is null until the first poll;
     * {@code lastFailureCode} is an {@code EgressFailure} name with its fixed message, and clears on the next
     * successful activation.
     */
    public record RuntimeHealth(Boolean controllerReachable, Instant lastPollAt, Instant lastActivatedAt, int activeNodes,
                                String lastFailureCode, String lastFailureMessage, Instant lastFailureAt) {}
    /**
     * {@code mihomoAvailable} is whether the deployment provides Mihomo (its controller secret is set);
     * {@code mihomoEnabled} is the effective switch: available and switched on by an administrator.
     */
    public record Catalog(List<Channel> channels, List<Subscription> subscriptions, boolean mihomoAvailable,
                          boolean mihomoEnabled, boolean tlsEnabled, List<String> tlsProfiles, RuntimeHealth runtime) {}
    public record UpdateRuntime(@NotNull Boolean mihomoEnabled) {}
    /** An account bound to a node; {@code channelId} and {@code channelName} say which node of a subscription. */
    public record BoundAccount(String accountId, String email, String provider, String authMethod, boolean enabled,
                               UUID channelId, String channelName) {}
    public record CreateChannel(@NotBlank @Size(max = 200) String name,
                                @Size(max = 4096) String proxyUrl,
                                @Size(max = 65536) String clashYaml) {}
    public record UpdateChannel(@Size(max = 200) String name, Boolean enabled) {}
    public record RenameSubscription(@NotBlank @Size(max = 160) String name) {}
    public record ImportSubscription(@NotBlank @Size(max = 160) String name,
                                     @Size(max = 8192) String url,
                                     @Size(max = 4194304) String yaml) {}
    public record SyncResult(UUID subscriptionId, int imported, int unavailable, List<String> issues) {}
    public record ProbeResult(boolean success, String exitIp, long latencyMs, String error, String errorCode) {}
}
