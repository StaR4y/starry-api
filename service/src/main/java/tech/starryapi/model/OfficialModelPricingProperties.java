package tech.starryapi.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "starry.model-pricing")
public class OfficialModelPricingProperties {

    private boolean remoteRefreshEnabled = true;
    private boolean syncCatalogIds = false;
    private String sourceUrl = "https://raw.githubusercontent.com/Wei-Shaw/model-price-repo/main/model_prices_and_context_window.json";
    private String checksumUrl = "https://raw.githubusercontent.com/Wei-Shaw/model-price-repo/main/model_prices_and_context_window.sha256";
    private int maxCatalogBytes = 4 * 1024 * 1024;
    private String cacheDir = System.getProperty("user.home") + "/.starry-api/model-pricing";
    private String refreshInterval = "PT10M";

    public boolean isRemoteRefreshEnabled() {
        return remoteRefreshEnabled;
    }

    public void setRemoteRefreshEnabled(boolean remoteRefreshEnabled) {
        this.remoteRefreshEnabled = remoteRefreshEnabled;
    }

    public boolean isSyncCatalogIds() {
        return syncCatalogIds;
    }

    public void setSyncCatalogIds(boolean syncCatalogIds) {
        this.syncCatalogIds = syncCatalogIds;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getChecksumUrl() {
        return checksumUrl;
    }

    public void setChecksumUrl(String checksumUrl) {
        this.checksumUrl = checksumUrl;
    }

    public int getMaxCatalogBytes() {
        return maxCatalogBytes;
    }

    public void setMaxCatalogBytes(int maxCatalogBytes) {
        this.maxCatalogBytes = maxCatalogBytes;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public String getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(String refreshInterval) {
        this.refreshInterval = refreshInterval;
    }
}
