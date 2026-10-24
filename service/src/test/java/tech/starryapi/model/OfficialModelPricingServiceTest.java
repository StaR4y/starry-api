package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import tech.starryapi.platform.catalog.ModelPriceRepoModelIds;
import tech.starryapi.model.OfficialModelPricingService.OfficialModelPrice;
import tech.starryapi.model.OfficialPriceResolver.OfficialPriceMatch;
import tools.jackson.databind.json.JsonMapper;

class OfficialModelPricingServiceTest {

    private final OfficialModelPricingService service = new OfficialModelPricingService(
        WebClient.builder().build(),
        JsonMapper.builder().build(),
        new OfficialModelPricingProperties()
    );

    @Test
    void loadsBundledCatalogAndResolvesProviderPrefixes() {
        OfficialModelPrice direct = service.find("gpt-5.5").orElseThrow();
        OfficialModelPrice prefixed = service.find("openai/gpt-5.5").orElseThrow();

        assertThat(service.size()).isGreaterThan(200);
        assertThat(direct.inputCostPerToken()).isEqualByComparingTo(new BigDecimal("0.000005"));
        assertThat(direct.outputCostPerToken()).isEqualByComparingTo(new BigDecimal("0.00003"));
        assertThat(prefixed).isEqualTo(direct);
    }

    @Test
    void resolvesCatalogAliasesWithoutUsingRoutingTargets() {
        OfficialPriceMatch gpt = service.resolve("gpt-5.5", true).orElseThrow();
        OfficialPriceMatch sonnet = service.resolve("sonnet", true).orElseThrow();
        OfficialPriceMatch dotted = service.resolve("claude-sonnet-4.5", true).orElseThrow();
        OfficialPriceMatch alias = service.resolve("claude-4-sonnet", true).orElseThrow();

        assertThat(gpt.price()).isEqualTo(service.find("gpt-5.5").orElseThrow());
        assertThat(gpt.matchedId()).isEqualTo("gpt-5.5");
        assertThat(sonnet.price().inputCostPerToken()).isPositive();
        assertThat(sonnet.matchedId()).contains("sonnet");
        assertThat(dotted.matchedId()).isEqualTo("claude-sonnet-4-5");
        assertThat(alias.price().inputCostPerToken()).isPositive();
        assertThat(alias.matchedId()).contains("sonnet");
        assertThat(service.find("claude-sonnet-4.5")).isPresent();
        assertThat(service.find("chatgpt-4o")).isPresent();
        assertThat(service.resolve("gpt-6", true).orElseThrow().matchedId()).contains("gpt-6");
        assertThat(service.resolve("gemini-1.5-pro", true).orElseThrow().price().inputCostPerToken()).isPositive();
    }

    @Test
    void resolvesEveryDefaultModelIdSourcedFromTheBundledRepositoryCatalog() {
        List<String> defaultModelIds = java.util.stream.Stream.of(
            ModelPriceRepoModelIds.OPENAI,
            ModelPriceRepoModelIds.ANTHROPIC,
            ModelPriceRepoModelIds.GOOGLE,
            ModelPriceRepoModelIds.DEEPSEEK
        ).flatMap(List::stream).toList();

        assertThat(defaultModelIds).hasSize(139).allSatisfy(modelId ->
            assertThat(service.find(modelId))
                .as("official price for %s", modelId)
                .isPresent()
        );
    }

    @Test
    void requiresBothPricesAndPreservesExplicitZeroCosts() {
        byte[] json = ("""
            {
              "usable": {
                "input_cost_per_token": 0.000001,
                "output_cost_per_token": 0.000002,
                "cache_creation_input_token_cost": 0
              },
              "missing-output": {"input_cost_per_token": 0.000001},
              "free": {"input_cost_per_token": 0, "output_cost_per_token": 0},
              "invalid": {"input_cost_per_token": -1, "output_cost_per_token": 1}
            }
            """).getBytes(StandardCharsets.UTF_8);

        Map<String, OfficialModelPrice> parsed = service.parseCatalog(json);

        assertThat(parsed).containsOnlyKeys("usable", "free");
        assertThat(parsed.get("usable").cacheWriteCostPerToken()).isZero();
        assertThat(parsed.get("usable").cacheReadCostPerToken()).isEqualByComparingTo("0.000001");
        assertThat(parsed.get("free").inputCostPerToken()).isZero();
        assertThat(parsed.get("free").outputCostPerToken()).isZero();
    }

    @Test
    void refreshesOnlyWhenDownloadedCatalogMatchesChecksum() throws Exception {
        byte[] remoteCatalog = """
            {
              "remote-model": {
                "input_cost_per_token": 0.000003,
                "output_cost_per_token": 0.000009
              }
            }
            """.getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(remoteCatalog));
        OfficialModelPricingService refreshed = remoteService(remoteCatalog, checksum);

        refreshed.refresh().block();

        assertThat(refreshed.find("remote-model")).isPresent();
        assertThat(refreshed.find("gpt-5.5")).isEmpty();

        OfficialModelPricingService rejected = remoteService(remoteCatalog, "0".repeat(64));
        assertThatThrownBy(() -> rejected.refresh().block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("checksum does not match");
        assertThat(rejected.find("gpt-5.5")).isPresent();
        assertThat(rejected.find("remote-model")).isEmpty();
    }

    @Test
    void appendsOfficialGoogleChatIdsWhenCatalogSyncIsEnabled() throws Exception {
        byte[] remoteCatalog = """
            {
              "gemini-brand-new-sync": {
                "litellm_provider": "gemini",
                "mode": "chat",
                "input_cost_per_token": 0.000001,
                "output_cost_per_token": 0.000002,
                "supported_endpoints": ["/v1/chat/completions"]
              }
            }
            """.getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(remoteCatalog));
        DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
        OfficialModelPricingService disabled = remoteService(remoteCatalog, checksum, catalog, false);

        disabled.refresh().block();
        assertThat(catalog.models()).noneMatch(model -> "gemini-brand-new-sync".equals(model.id())
            || "google/gemini-brand-new-sync".equals(model.id()));

        OfficialModelPricingService enabled = remoteService(remoteCatalog, checksum, catalog, true);
        enabled.refresh().block();
        assertThat(catalog.models()).extracting(ModelDtos.ModelSpec::id)
            .contains("gemini-brand-new-sync", "google/gemini-brand-new-sync");
        assertThat(catalog.models())
            .filteredOn(model -> "google/gemini-brand-new-sync".equals(model.id()))
            .singleElement()
            .satisfies(model -> {
                assertThat(model.provider()).isEqualTo("Google");
                assertThat(model.family()).isEqualTo("Google");
            });
    }

    @Test
    void groupsNumberedGeminiFamiliesFromOfficialIds() {
        assertThat(tech.starryapi.platform.catalog.PlatformModelCatalogSupport.googleFamily("google/gemini-3.8-flash"))
            .isEqualTo("Gemini 3.8");
        assertThat(tech.starryapi.platform.catalog.PlatformModelCatalogSupport.googleFamily("gemini-3-flash"))
            .isEqualTo("Gemini 3");
        assertThat(tech.starryapi.platform.catalog.PlatformModelCatalogSupport.googleFamily("gemini-2.0-flash"))
            .isEqualTo("Gemini 2.0");
    }

    @Test
    void appliesAlreadyLoadedOfficialIdsWhenSyncIsTurnedOnWithoutRefresh() throws Exception {
        byte[] remoteCatalog = """
            {
              "gemini-brand-new-sync": {
                "litellm_provider": "gemini",
                "mode": "chat",
                "input_cost_per_token": 0.000001,
                "output_cost_per_token": 0.000002,
                "supported_endpoints": ["/v1/chat/completions"]
              }
            }
            """.getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(remoteCatalog));
        DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
        OfficialModelPricingService service = remoteService(remoteCatalog, checksum, catalog, false);

        service.refresh().block();
        assertThat(catalog.models()).noneMatch(model -> "google/gemini-brand-new-sync".equals(model.id()));

        service.updateSource(new ModelDtos.UpdateModelPricingSourceRequest(false, true, null, null, false))
            .block();

        assertThat(catalog.models()).extracting(ModelDtos.ModelSpec::id)
            .contains("google/gemini-brand-new-sync");
        assertThat(catalog.models())
            .filteredOn(model -> model.provider() != null && model.provider().equalsIgnoreCase("Gemini"))
            .isEmpty();
    }

    private OfficialModelPricingService remoteService(byte[] catalog, String checksum) {
        return remoteService(catalog, checksum, null, false);
    }

    private OfficialModelPricingService remoteService(
        byte[] catalog,
        String checksum,
        DefaultModelCatalogProvider catalogProvider,
        boolean syncCatalogIds
    ) {
        OfficialModelPricingProperties properties = new OfficialModelPricingProperties();
        properties.setSourceUrl("https://pricing.test/catalog.json");
        properties.setChecksumUrl("https://pricing.test/catalog.sha256");
        properties.setSyncCatalogIds(syncCatalogIds);
        ExchangeFunction exchange = request -> {
            boolean checksumRequest = request.url().toString().equals(properties.getChecksumUrl());
            String body = checksumRequest ? checksum : new String(catalog, StandardCharsets.UTF_8);
            return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build());
        };
        return new OfficialModelPricingService(
            WebClient.builder().exchangeFunction(exchange).build(),
            JsonMapper.builder().build(),
            properties,
            null,
            catalogProvider
        );
    }
}
