package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogProvider;

class ProxyModelServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();

    @Test
    void exposesOfficialHiddenAndPresetModels() {
        JsonNode response = objectMapper.valueToTree(modelCatalog.modelList(123L));
        JsonNode data = response.path("data");
        List<String> ids = data.findValuesAsText("id");

        assertThat(response.path("models")).hasSize(data.size());
        assertThat(response.path("models").get(0).path("id").asText()).isEqualTo(data.get(0).path("id").asText());
        assertThat(ids)
            .hasSizeGreaterThanOrEqualTo(142)
            .contains(
                "auto",
                "claude-sonnet-4.5",
                "claude-sonnet-4",
                "claude-4-sonnet",
                "claude-4-opus",
                "claude-opus-4",
                "claude-opus-4.8",
                "claude-opus-4-8",
                "claude-sonnet-5",
                "claude-opus-5",
                "claude-sonnet-4.6",
                "claude-sonnet-latest",
                "sonnet",
                "claude-haiku-4.5",
                "claude-haiku-4-5-20251001",
                "claude-haiku-latest",
                "haiku",
                "claude-opus-4.5",
                "claude-opus-4.7",
                "claude-opus-4-7",
                "claude-opus-latest",
                "opus",
                "claude-3.5-sonnet",
                "claude-3.7-sonnet",
                "simple-task",
                "CLAUDE_SONNET_4_20250514_V1_0",
                "CLAUDE_HAIKU_4_5_20251001_V1_0",
                "CLAUDE_3_7_SONNET_20250219_V1_0",
                "anthropic/claude-opus-4-8",
                "anthropic/claude-opus-5",
                "anthropic/claude-sonnet-4-5",
                "anthropic/claude-sonnet-4-6",
                "anthropic/claude-haiku-4-5-20251001",
                "gpt-6",
                "gpt-6-astra",
                "gpt-6-mini",
                "gpt-5.6",
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.2",
                "gpt-5",
                "gpt-4o-mini",
                "openai/gpt-5.5",
                "openai/gpt-4o-mini",
                "openai/o4-mini-2025-04-16",
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "google/gemini-3.5-flash",
                "google/gemini-2.5-pro",
                "google/gemini-3.7-flash"
            );
        assertThat(model(data, "auto").path("owned_by").asText()).isEqualTo("anthropic");
        assertThat(model(data, "auto").path("description").asText()).isEqualTo("Auto select best model");
        assertThat(model(data, "simple-task").path("supportedInputTypes").get(0).asText()).isEqualTo("TEXT");
        assertThat(model(data, "simple-task").path("maxOutputTokens").asInt()).isEqualTo(4096);
        assertThat(model(data, "openai/gpt-5.5").path("owned_by").asText()).isEqualTo("starryapi");
        assertThat(model(data, "openai/gpt-5.5").path("provider").asText()).isEqualTo("OpenAI");
        assertThat(model(data, "gpt-6").path("family").asText()).isEqualTo("GPT-6");
        assertThat(model(data, "openai/gpt-5.5").path("family").asText()).isEqualTo("GPT-5");
        assertThat(model(data, "openai/gpt-5.5").path("targetModelId").asText()).isEqualTo("claude-sonnet-4.5");
        assertThat(model(data, "google/gemini-3.5-flash").path("provider").asText()).isEqualTo("Google");
        assertThat(model(data, "google/gemini-3.5-flash").path("family").asText()).isEqualTo("Gemini 3.5");
        assertThat(model(data, "anthropic/claude-sonnet-4-5").path("provider").asText()).isEqualTo("Anthropic");
        assertThat(model(data, "anthropic/claude-sonnet-4-5").path("family").asText()).isEqualTo("Claude 4");
    }

    @Test
    void mapsSimpleTaskAliasToHaiku() {
        assertThat(modelCatalog.resolveTargetModel("simple-task")).isEqualTo("claude-haiku-4.5");
    }

    @Test
    void mapsOpenAiAliasesToKiroModels() {
        assertThat(modelCatalog.resolveTargetModel("gpt-6")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("gpt-6-mini")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("gpt-5.5")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("gpt-5.2")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("gpt-4o-mini")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("o3")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("o4-mini")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("openai/gpt-5.5-pro")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("openai/gpt-4o-mini")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("openai/o4-mini-high")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("gpt-unknown-future")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("openai/gpt-unknown-future")).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void mapsGeminiAliasesToKiroModels() {
        assertThat(modelCatalog.resolveTargetModel("gemini-2.5-pro")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("gemini-2.5-flash")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("google/gemini-2.5-pro")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("google/gemini-3.5-flash")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("google/gemma-4-31b-it:free")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("gemini-future")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("google/gemini-future")).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void mapsAnthropicRepositoryAliasesToKiroModels() {
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-sonnet-4-5")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-sonnet-4-6")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("claude-sonnet-latest")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("sonnet")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-haiku-4-5")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-haiku-4-5-20251001")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("haiku")).isEqualTo("claude-haiku-4.5");
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-opus-4-5")).isEqualTo("claude-opus-4.5");
        assertThat(modelCatalog.resolveTargetModel("claude-opus-latest")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("opus")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelCatalog.resolveTargetModel("claude-opus-4.8")).isEqualTo("claude-opus-4.5");
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-opus-4-8")).isEqualTo("claude-opus-4.5");
        assertThat(modelCatalog.resolveTargetModel("claude-4-sonnet")).isEqualTo("claude-sonnet-4");
        assertThat(modelCatalog.resolveTargetModel("anthropic/claude-future")).isEqualTo("claude-sonnet-4.5");
    }

    private JsonNode model(JsonNode data, String id) {
        for (JsonNode item : data) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Model not found: " + id);
    }
}
