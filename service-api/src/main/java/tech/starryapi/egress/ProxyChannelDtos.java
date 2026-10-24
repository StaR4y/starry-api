package tech.starryapi.egress;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProxyChannelDtos {
    private ProxyChannelDtos() {}

    public record Channel(UUID channelId, UUID subscriptionId, String name, String protocol,
                          boolean enabled, String runtimeStatus, String runtimeError,
                          long boundAccounts, Instant updatedAt) {}
    public record Subscription(UUID subscriptionId, String name, String status,
                               Instant lastSyncedAt, String lastError, long nodeCount, boolean refreshable) {}
    public record Catalog(List<Channel> channels, List<Subscription> subscriptions, boolean mihomoEnabled,
                          boolean tlsEnabled, List<String> tlsProfiles) {}
    public record CreateChannel(@NotBlank @Size(max = 200) String name,
                                @Size(max = 4096) String proxyUrl,
                                @Size(max = 65536) String clashYaml) {}
    public record UpdateChannel(@Size(max = 200) String name, Boolean enabled) {}
    public record ImportSubscription(@NotBlank @Size(max = 160) String name,
                                     @Size(max = 8192) String url,
                                     @Size(max = 4194304) String yaml) {}
    public record SyncResult(UUID subscriptionId, int imported, int unavailable, List<String> issues) {}
    public record ProbeResult(boolean success, String exitIp, long latencyMs, String error, String errorCode) {}
}
