package tech.starryapi.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tech.starryapi.proxy.tier.RequestBounds;

@ConfigurationProperties(prefix = "starry.proxy")
public class ProxyProperties {

    /** What a Responses request that names no output limit is given, when the model writes that much. */
    public static final long DEFAULT_RESPONSES_OUTPUT_TOKENS = 16_000L;
    /** The least the default may be set to: the 4,096 the gateway applied to such a request before it had a configurable default. The setting raises it, it does not cut answers shorter. */
    public static final long MIN_RESPONSES_OUTPUT_TOKENS = RequestBounds.FALLBACK_OUTPUT_TOKENS;
    /** The most the default may be set to: the most output a client itself may ask for. */
    public static final long MAX_RESPONSES_OUTPUT_TOKENS = RequestBounds.MAX_OUTPUT_TOKENS;

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
    private long responsesDefaultOutputTokens = DEFAULT_RESPONSES_OUTPUT_TOKENS;
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

    /**
     * The output limit of a Responses request that names none (Codex never does): what the reservation and the native sources
     * (Kiro, official OpenAI keys, Anthropic keys) are held to; the model's own maximum output is the most it is ever given. A
     * relay is not given it: it is given the maximum output its mapping states, up to this, or 4,096. A value outside the
     * range is refused at startup.
     */
    public long getResponsesDefaultOutputTokens() {
        return responsesDefaultOutputTokens;
    }

    public void setResponsesDefaultOutputTokens(long responsesDefaultOutputTokens) {
        if (responsesDefaultOutputTokens < MIN_RESPONSES_OUTPUT_TOKENS || responsesDefaultOutputTokens > MAX_RESPONSES_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("starry.proxy.responses-default-output-tokens (STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS)"
                + " must be between " + MIN_RESPONSES_OUTPUT_TOKENS + " and " + MAX_RESPONSES_OUTPUT_TOKENS
                + ", not " + responsesDefaultOutputTokens);
        }
        this.responsesDefaultOutputTokens = responsesDefaultOutputTokens;
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
