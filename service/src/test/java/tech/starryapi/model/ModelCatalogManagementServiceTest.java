package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.ObjectMapper;

class ModelCatalogManagementServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SystemSettingRepository repository = mock(SystemSettingRepository.class);
    private final DefaultModelCatalogProvider provider = new DefaultModelCatalogProvider();
    private final Map<String, String> stored = new HashMap<>();
    private ModelCatalogManagementService service;

    @BeforeEach
    void setUp() {
        stored.clear();
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String value = stored.get(invocation.getArgument(0).toString());
            return value == null ? Mono.empty() : Mono.just(setting(invocation.getArgument(0), value));
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return Mono.just(1);
        });
        when(repository.upsertSettings(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                String[] keys = invocation.getArgument(0);
                String[] values = invocation.getArgument(1);
                for (int index = 0; index < keys.length; index++) {
                    stored.put(keys[index], values[index]);
                }
                return Mono.just(keys.length);
            });
        service = new ModelCatalogManagementService(repository, objectMapper, provider);
    }

    @Test
    void persistsAndActivatesCustomModel() throws Exception {
        ModelDtos.ManagedModelResponse result = service.upsert(request(
            "team-model",
            "Team Model",
            "https://gateway.example.com/v1/chat/completions",
            320_000,
            true
        )).block();

        assertThat(result).isNotNull();
        assertThat(result.builtIn()).isFalse();
        assertThat(result.badgeColor()).isEqualTo("#4D6BFE");
        assertThat(result.contextWindow()).isEqualTo(320_000);
        assertThat(provider.resolveTargetModel("team-model")).isEqualTo("claude-sonnet-4.5");
        assertThat(objectMapper.readTree(customizationsJson()).get(0).path("endpoint").asString(""))
            .isEqualTo("https://gateway.example.com/v1/chat/completions");
    }

    @Test
    void deletingBuiltInModelCreatesRestorableTombstone() {
        service.delete("auto").block();

        assertThat(provider.models()).noneMatch(model -> model.id().equals("auto"));
        assertThat(provider.managedModels())
            .filteredOn(model -> model.id().equals("auto"))
            .singleElement()
            .extracting(ModelDtos.ManagedModelResponse::enabled)
            .isEqualTo(false);
        assertThat(customizationsJson()).contains("\"id\":\"auto\"").contains("\"enabled\":false");
    }

    @Test
    void deletingCustomModelRemovesItsConfiguration() {
        service.upsert(request("team-model", "Team Model", "/v1/chat/completions", 200_000, true)).block();

        service.delete("team-model").block();

        assertThat(customizationsJson()).isEqualTo("[]");
        assertThat(provider.managedModels()).noneMatch(model -> model.id().equals("team-model"));
    }

    @Test
    void deletingCustomVendorRemovesAllOfItsModels() {
        service.upsert(request("team-model", "Team Model", "/v1/chat/completions", 200_000, true)).block();
        service.upsert(request("second-model", "Second Model", "/v1/chat/completions", 128_000, true)).block();

        service.deleteVendor("Internal").block();

        assertThat(provider.managedModels())
            .noneMatch(model -> model.id().equals("team-model") || model.id().equals("second-model"));
        assertThat(customizationsJson()).isEqualTo("[]");
    }

    @Test
    void rejectsDeletingBuiltInVendors() {
        assertThatThrownBy(() -> service.deleteVendor("OpenAI").block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("MODEL_VENDOR_PROTECTED");
            });
    }

    @Test
    void rejectsDeletingVendorThatStillContainsBuiltInModels() {
        service.upsert(new ModelDtos.UpsertManagedModelRequest(
            "auto",
            "Auto",
            null,
            "Internal",
            "Router",
            "claude-haiku-4.5",
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            200_000,
            null,
            true
        )).block();

        assertThatThrownBy(() -> service.deleteVendor("Internal").block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("MODEL_VENDOR_HAS_BUILTIN");
            });
    }

    @Test
    void persistsDisplayOrderAndAppendsNewlyCreatedModels() {
        service.upsert(request("team-model", "Team Model", "/v1/chat/completions", 200_000, true)).block();
        service.reorder(new ModelDtos.ReorderManagedModelsRequest(List.of("team-model", "auto"))).block();

        assertThat(provider.managedModels()).extracting(ModelDtos.ManagedModelResponse::id)
            .startsWith("team-model", "auto");
        assertThat(objectMapper.valueToTree(stored.get(ModelCatalogManagementService.MODEL_CATALOG_ORDER)).toString())
            .contains("team-model")
            .contains("auto");

        service.upsert(request("second-model", "Second Model", "/v1/chat/completions", 128_000, true)).block();
        assertThat(provider.managedModels()).extracting(ModelDtos.ManagedModelResponse::id)
            .startsWith("team-model", "auto")
            .contains("second-model");
        assertThat(stored.get(ModelCatalogManagementService.MODEL_CATALOG_ORDER)).contains("second-model");
    }

    @Test
    void restoresDefaultCatalogOrder() {
        service.upsert(request("team-model", "Team Model", "/v1/chat/completions", 200_000, true)).block();
        service.reorder(new ModelDtos.ReorderManagedModelsRequest(List.of("team-model", "auto"))).block();

        service.reorder(new ModelDtos.ReorderManagedModelsRequest(List.of(), true)).block();

        assertThat(stored.get(ModelCatalogManagementService.MODEL_CATALOG_ORDER)).isEqualTo("[]");
        assertThat(provider.models().getFirst().id()).isNotEqualTo("team-model");
    }

    @Test
    void persistsCatalogDisplaySettingsForAllUsers() {
        ModelDtos.ModelCatalogDisplaySettings saved = service.updateDisplay(
            new ModelDtos.UpdateModelCatalogDisplayRequest(
                "list",
                "compact",
                3,
                false,
                true,
                false,
                true,
                true,
                "none",
                "outline",
                null
            )
        ).block();

        assertThat(saved).isNotNull();
        assertThat(saved.layout()).isEqualTo("list");
        assertThat(saved.density()).isEqualTo("compact");
        assertThat(saved.columns()).isEqualTo(3);
        assertThat(saved.showIcon()).isFalse();
        assertThat(saved.showDescription()).isTrue();
        assertThat(saved.groupBy()).isEqualTo("none");
        assertThat(saved.cardStyle()).isEqualTo("outline");
        assertThat(service.displaySettings().block()).isEqualTo(saved);
        assertThat(service.viewSettings().block()).isEqualTo(
            new ModelDtos.ModelCatalogViewSettings(saved, List.of())
        );

        ModelDtos.ModelCatalogDisplaySettings restored = service.updateDisplay(
            new ModelDtos.UpdateModelCatalogDisplayRequest(
                null, null, null, null, null, null, null, null, null, null, true
            )
        ).block();
        assertThat(restored).isEqualTo(ModelDtos.ModelCatalogDisplaySettings.defaults());
    }

    @Test
    void rejectsInvalidDisplayLayout() {
        assertThatThrownBy(() -> service.updateDisplay(
            new ModelDtos.UpdateModelCatalogDisplayRequest(
                "masonry", null, null, null, null, null, null, null, null, null, null
            )
        ).block()).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.getCode()).isEqualTo("MODEL_DISPLAY_LAYOUT_INVALID");
        });
    }

    @Test
    void requiresBadgeColorForCustomVendors() {
        ModelDtos.UpsertManagedModelRequest request = new ModelDtos.UpsertManagedModelRequest(
            "or-kimi",
            "Kimi",
            null,
            "OpenRouter",
            "Custom",
            "or-kimi",
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            128_000,
            null,
            true,
            null
        );

        assertThatThrownBy(() -> service.upsert(request))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("MODEL_BADGE_COLOR_REQUIRED");
            });
    }

    @Test
    void rejectsUnsupportedRequestFormat() {
        ModelDtos.UpsertManagedModelRequest request = new ModelDtos.UpsertManagedModelRequest(
            "team-model",
            "Team Model",
            null,
            "Internal",
            "Team",
            "claude-sonnet-4.5",
            "ANTHROPIC_MESSAGES",
            "/v1/messages",
            200_000,
            null,
            true
        );

        assertThatThrownBy(() -> service.upsert(request))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("MODEL_REQUEST_FORMAT_UNSUPPORTED");
            });
    }

    private ModelDtos.UpsertManagedModelRequest request(
        String id,
        String name,
        String endpoint,
        int contextWindow,
        boolean enabled
    ) {
        return new ModelDtos.UpsertManagedModelRequest(
            id,
            name,
            "https://cdn.example.com/model.png",
            "Internal",
            "Team",
            "claude-sonnet-4.5",
            "OPENAI_CHAT_COMPLETIONS",
            endpoint,
            contextWindow,
            "Managed model",
            enabled,
            "#4D6BFE"
        );
    }

    private String customizationsJson() {
        return stored.getOrDefault(ModelCatalogManagementService.MODEL_CATALOG_CUSTOMIZATIONS, "[]");
    }

    private SystemSettingEntity setting(String key, String json) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key.toString());
        entity.setSettingValue(json);
        return entity;
    }
}
