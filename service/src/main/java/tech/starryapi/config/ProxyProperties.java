package tech.starryapi.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "starry.proxy")
public class ProxyProperties {

    private String adminToken = "";
    private String webuiUrl = "";
    private boolean allowAnonymousProxy;
    private Duration requestTimeout = Duration.ofSeconds(300);
    private Duration adminSessionTtl = Duration.ofHours(12);
    private int maxRetries = 2;
    private int rateLimitPerMinute = 120;
    private AccountSelectionStrategy accountSelectionStrategy = AccountSelectionStrategy.ROUND_ROBIN;
    private boolean sessionAffinityEnabled = true;
    private Duration sessionAffinityTtl = Duration.ofMinutes(10);
    private long sessionAffinityMaximumSize = 100_000;
    private Duration baseCooldown = Duration.ofMinutes(1);
    private Duration quotaReset = Duration.ofHours(1);
    private double probabilisticRetryChance = 0.1;
    private long maxRequestBodyBytes = 10 * 1024 * 1024;
    private Kiro kiro = new Kiro();

    public enum AccountSelectionStrategy {
        ROUND_ROBIN,
        STICKY
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public String getWebuiUrl() {
        return webuiUrl;
    }

    public void setWebuiUrl(String webuiUrl) {
        this.webuiUrl = webuiUrl;
    }

    public boolean isAllowAnonymousProxy() {
        return allowAnonymousProxy;
    }

    public void setAllowAnonymousProxy(boolean allowAnonymousProxy) {
        this.allowAnonymousProxy = allowAnonymousProxy;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getAdminSessionTtl() {
        return adminSessionTtl;
    }

    public void setAdminSessionTtl(Duration adminSessionTtl) {
        this.adminSessionTtl = adminSessionTtl;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public AccountSelectionStrategy getAccountSelectionStrategy() {
        return accountSelectionStrategy;
    }

    public void setAccountSelectionStrategy(AccountSelectionStrategy accountSelectionStrategy) {
        this.accountSelectionStrategy = accountSelectionStrategy;
    }

    public boolean isSessionAffinityEnabled() {
        return sessionAffinityEnabled;
    }

    public void setSessionAffinityEnabled(boolean sessionAffinityEnabled) {
        this.sessionAffinityEnabled = sessionAffinityEnabled;
    }

    public Duration getSessionAffinityTtl() {
        return sessionAffinityTtl;
    }

    public void setSessionAffinityTtl(Duration sessionAffinityTtl) {
        this.sessionAffinityTtl = sessionAffinityTtl;
    }

    public long getSessionAffinityMaximumSize() {
        return sessionAffinityMaximumSize;
    }

    public void setSessionAffinityMaximumSize(long sessionAffinityMaximumSize) {
        this.sessionAffinityMaximumSize = sessionAffinityMaximumSize;
    }

    public Duration getBaseCooldown() {
        return baseCooldown;
    }

    public void setBaseCooldown(Duration baseCooldown) {
        this.baseCooldown = baseCooldown;
    }

    public Duration getQuotaReset() {
        return quotaReset;
    }

    public void setQuotaReset(Duration quotaReset) {
        this.quotaReset = quotaReset;
    }

    public double getProbabilisticRetryChance() {
        return probabilisticRetryChance;
    }

    public void setProbabilisticRetryChance(double probabilisticRetryChance) {
        this.probabilisticRetryChance = probabilisticRetryChance;
    }

    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public Kiro getKiro() {
        return kiro;
    }

    public void setKiro(Kiro kiro) {
        this.kiro = kiro;
    }

    /** Amazon CodeWhisperer / Q endpoints that Kiro accounts are sent to. */
    public static class Kiro {
        private String preferredEndpoint = "codewhisperer";
        private List<Endpoint> endpoints = new ArrayList<>();

        public String getPreferredEndpoint() {
            return preferredEndpoint;
        }

        public void setPreferredEndpoint(String preferredEndpoint) {
            this.preferredEndpoint = preferredEndpoint;
        }

        public List<Endpoint> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(List<Endpoint> endpoints) {
            this.endpoints = endpoints;
        }
    }

    public static class Endpoint {
        private String name;
        private String url;
        private String origin;
        private String target;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getOrigin() {
            return origin;
        }

        public void setOrigin(String origin) {
            this.origin = origin;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }
}
