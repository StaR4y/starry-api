package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.ModelRankingDtos.RankingSnapshot;

class ModelRankingServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsBundledSnapshotsWhenCacheIsEmpty() {
        ModelRankingService service = service(WebClient.builder().build(), false);

        RankingSnapshot program = service.snapshot(ModelRankingDtos.PROGRAM_BENCH);
        RankingSnapshot frontier = service.snapshot(ModelRankingDtos.FRONTIER_BENCH);

        assertThat(program.title()).contains("ProgramBench");
        assertThat(program.entries()).isNotEmpty().hasSizeLessThanOrEqualTo(12);
        assertThat(program.entries().get(0).model()).isNotBlank();
        assertThat(frontier.title()).contains("Terminal-Bench");
        assertThat(frontier.entries()).isNotEmpty();
        assertThat(service.isDue(ModelRankingDtos.PROGRAM_BENCH)).isTrue();
    }

    @Test
    void rejectsUnknownBenchmarks() {
        ModelRankingService service = service(WebClient.builder().build(), false);

        assertThatThrownBy(() -> service.snapshot("unknown"))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("MODEL_RANKING_NOT_FOUND")
            );
    }

    @Test
    void refreshesFromRemoteAndPersistsCache() throws Exception {
        String payload = """
            {
              "title": "Remote ProgramBench",
              "sourceUrl": "https://example.test/program",
              "entries": [
                {"rank": 2, "model": "Beta", "organization": "Org", "agent": "agent", "score": 8.5},
                {"rank": 1, "model": "Alpha", "organization": "Org", "agent": "agent", "score": 12.0, "secondaryScore": 3.5}
              ]
            }
            """;
        ModelRankingService service = service(remoteClient(payload), true);

        RankingSnapshot snapshot = service.refreshOne(ModelRankingDtos.PROGRAM_BENCH).block();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.title()).isEqualTo("Remote ProgramBench");
        assertThat(snapshot.entries()).extracting(ModelRankingDtos.RankingEntry::model)
            .containsExactly("Alpha", "Beta");
        assertThat(snapshot.entries().get(0).secondaryScore()).isEqualTo(3.5);
        assertThat(snapshot.fetchedAt()).isNotNull();
        assertThat(service.snapshot(ModelRankingDtos.PROGRAM_BENCH).title()).isEqualTo("Remote ProgramBench");
        assertThat(Files.exists(tempDirectory.resolve("programbench.json"))).isTrue();
        assertThat(service.isDue(ModelRankingDtos.PROGRAM_BENCH)).isFalse();

        ModelRankingService reloaded = service(WebClient.builder().build(), false);
        assertThat(reloaded.snapshot(ModelRankingDtos.PROGRAM_BENCH).title()).isEqualTo("Remote ProgramBench");
        assertThat(reloaded.isDue(ModelRankingDtos.PROGRAM_BENCH)).isFalse();
    }

    @Test
    void keepsStoredSnapshotWhenRemoteRefreshFails() {
        ModelRankingService service = service(
            remoteClient(HttpStatus.BAD_GATEWAY, "upstream unavailable"),
            true
        );
        String bundledTitle = service.snapshot(ModelRankingDtos.PROGRAM_BENCH).title();

        assertThatThrownBy(() -> service.refreshOne(ModelRankingDtos.PROGRAM_BENCH).block())
            .isInstanceOf(RuntimeException.class);
        assertThat(service.snapshot(ModelRankingDtos.PROGRAM_BENCH).title()).isEqualTo(bundledTitle);
    }

    @Test
    void parsesOnlyUsableEntriesAndCapsLength() {
        ModelRankingProperties properties = properties(false);
        properties.setMaxEntries(2);
        ModelRankingService service = new ModelRankingService(
            WebClient.builder().build(),
            mapper(),
            properties
        );
        byte[] payload = """
            {
              "title": "Capped",
              "entries": [
                {"rank": 3, "model": "Third", "organization": "A", "agent": "x", "score": 1},
                {"rank": 1, "model": "First", "organization": "A", "agent": "x", "score": 9},
                {"model": "MissingRank", "score": 8},
                {"rank": 2, "model": "Second", "organization": "A", "agent": "x", "score": 7}
              ]
            }
            """.getBytes(StandardCharsets.UTF_8);

        RankingSnapshot snapshot = service.parse(ModelRankingDtos.FRONTIER_BENCH, payload, Instant.parse("2026-09-08T00:00:00Z"));

        assertThat(snapshot.entries()).extracting(ModelRankingDtos.RankingEntry::model)
            .containsExactly("First", "Second");
        assertThat(snapshot.fetchedAt()).isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    void adminSettingsExposeBundledSnapshots() {
        ModelRankingService service = service(WebClient.builder().build(), false);
        ModelRankingDtos.RankingSettingsResponse settings = service.adminSettings();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.remoteRefreshEnabled()).isFalse();
        assertThat(settings.maxEntries()).isEqualTo(12);
        assertThat(settings.benchmarks()).extracting(ModelRankingDtos.BenchmarkStatus::benchmark)
            .containsExactly(ModelRankingDtos.PROGRAM_BENCH, ModelRankingDtos.FRONTIER_BENCH);
        assertThat(settings.benchmarks().get(0).entryCount()).isPositive();
        assertThat(settings.benchmarks().get(0).due()).isTrue();
    }

    @Test
    void updateSettingsRejectsInvalidSourceUrl() {
        ModelRankingService service = service(WebClient.builder().build(), true);

        assertThatThrownBy(() -> service.updateSettings(new ModelRankingDtos.UpdateRankingSettingsRequest(
            null,
            null,
            null,
            "ftp://example.test/ranking.json",
            null,
            null
        )).block())
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("MODEL_RANKING_URL_INVALID")
            );
    }

    @Test
    void updateSettingsOverridesUrlsAndEntryLimit() {
        ModelRankingService service = service(WebClient.builder().build(), true);

        ModelRankingDtos.RankingSettingsResponse settings = service.updateSettings(
            new ModelRankingDtos.UpdateRankingSettingsRequest(
                false,
                false,
                8,
                "https://ranking.example/program.json",
                "https://ranking.example/frontier.json",
                null
            )
        ).block();

        assertThat(settings).isNotNull();
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.remoteRefreshEnabled()).isFalse();
        assertThat(settings.maxEntries()).isEqualTo(8);
        assertThat(settings.programbenchUrl()).isEqualTo("https://ranking.example/program.json");
        assertThat(settings.frontierbenchUrl()).isEqualTo("https://ranking.example/frontier.json");
    }

    @Test
    void refreshNowFetchesEvenWhenAutoSyncIsDisabled() {
        String payload = """
            {
              "title": "Forced ProgramBench",
              "sourceUrl": "https://example.test/program",
              "entries": [
                {"rank": 1, "model": "Alpha", "organization": "Org", "agent": "agent", "score": 9.0}
              ]
            }
            """;
        ModelRankingService service = service(remoteClient(payload), false);

        ModelRankingDtos.RankingSettingsResponse settings = service.refreshNow().block();

        assertThat(settings).isNotNull();
        assertThat(settings.lastError()).isNull();
        assertThat(service.snapshot(ModelRankingDtos.PROGRAM_BENCH).title()).isEqualTo("Forced ProgramBench");
        assertThat(service.snapshot(ModelRankingDtos.FRONTIER_BENCH).title()).isEqualTo("Forced ProgramBench");
    }

    @Test
    void disablingRankingHidesPublicSnapshots() {
        ModelRankingService service = service(WebClient.builder().build(), true);
        service.updateSettings(new ModelRankingDtos.UpdateRankingSettingsRequest(
            false, null, null, null, null, null
        )).block();

        assertThat(service.enabled()).isFalse();
        assertThatThrownBy(() -> service.snapshot(ModelRankingDtos.PROGRAM_BENCH))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("MODEL_RANKING_DISABLED")
            );
    }

    private ModelRankingService service(WebClient webClient, boolean remoteEnabled) {
        return new ModelRankingService(
            webClient,
            mapper(),
            properties(remoteEnabled)
        );
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder().build().findAndRegisterModules();
    }

    private ModelRankingProperties properties(boolean remoteEnabled) {
        ModelRankingProperties properties = new ModelRankingProperties();
        properties.setCacheDir(tempDirectory.toString());
        properties.setRemoteRefreshEnabled(remoteEnabled);
        properties.setProgrambenchUrl("https://ranking.test/programbench.json");
        properties.setFrontierbenchUrl("https://ranking.test/frontierbench.json");
        return properties;
    }

    private WebClient remoteClient(String body) {
        return remoteClient(HttpStatus.OK, body);
    }

    private WebClient remoteClient(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> reactor.core.publisher.Mono.just(
            ClientResponse.create(status).body(body).build()
        );
        return WebClient.builder().exchangeFunction(exchange).build();
    }
}
