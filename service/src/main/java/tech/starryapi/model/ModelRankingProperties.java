package tech.starryapi.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "starry.model-ranking")
public class ModelRankingProperties {

    private boolean enabled = true;
    private boolean remoteRefreshEnabled = true;
    private String cacheDir = System.getProperty("user.home") + "/.starry-api/model-ranking";
    private String refreshCron = "0 40 4 * * *";
    private int maxBytes = 1024 * 1024;
    private int maxEntries = 12;
    private String programbenchUrl =
        "https://raw.githubusercontent.com/StaR4y/ai-benchmark-ranking/main/data/programbench.json";
    private String frontierbenchUrl =
        "https://raw.githubusercontent.com/StaR4y/ai-benchmark-ranking/main/data/frontierbench.json";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRemoteRefreshEnabled() {
        return remoteRefreshEnabled;
    }

    public void setRemoteRefreshEnabled(boolean remoteRefreshEnabled) {
        this.remoteRefreshEnabled = remoteRefreshEnabled;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public String getRefreshCron() {
        return refreshCron;
    }

    public void setRefreshCron(String refreshCron) {
        this.refreshCron = refreshCron;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public String getProgrambenchUrl() {
        return programbenchUrl;
    }

    public void setProgrambenchUrl(String programbenchUrl) {
        this.programbenchUrl = programbenchUrl;
    }

    public String getFrontierbenchUrl() {
        return frontierbenchUrl;
    }

    public void setFrontierbenchUrl(String frontierbenchUrl) {
        this.frontierbenchUrl = frontierbenchUrl;
    }

    public String urlFor(String benchmark) {
        return switch (benchmark) {
            case ModelRankingDtos.PROGRAM_BENCH -> programbenchUrl;
            case ModelRankingDtos.FRONTIER_BENCH -> frontierbenchUrl;
            default -> throw new IllegalArgumentException("Unknown model ranking benchmark");
        };
    }
}
