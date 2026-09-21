package tech.starryapi.model;

import java.time.Instant;
import java.util.List;

public final class ModelRankingDtos {

    private ModelRankingDtos() {
    }

    public static final String PROGRAM_BENCH = "programbench";
    public static final String FRONTIER_BENCH = "frontierbench";

    public record RankingEntry(
        int rank,
        String model,
        String organization,
        String agent,
        double score,
        Double secondaryScore
    ) {
    }

    public record RankingSnapshot(
        String benchmark,
        String title,
        String sourceUrl,
        Instant fetchedAt,
        List<RankingEntry> entries
    ) {
    }

    public record BenchmarkStatus(
        String benchmark,
        String title,
        String sourceUrl,
        Instant fetchedAt,
        int entryCount,
        boolean due
    ) {
    }

    public record RankingPublicConfig(
        boolean enabled
    ) {
    }

    public record RankingSettingsResponse(
        boolean enabled,
        boolean remoteRefreshEnabled,
        String refreshCron,
        Integer maxEntries,
        String programbenchUrl,
        String frontierbenchUrl,
        String lastError,
        List<BenchmarkStatus> benchmarks
    ) {
    }

    public record UpdateRankingSettingsRequest(
        Boolean enabled,
        Boolean remoteRefreshEnabled,
        Integer maxEntries,
        String programbenchUrl,
        String frontierbenchUrl,
        Boolean refreshNow
    ) {
    }
}
