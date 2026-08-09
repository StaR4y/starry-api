package tech.starryapi.platform.catalog;

import java.util.List;
import java.util.stream.Stream;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.platform.anthropic.AnthropicModelCatalog;
import tech.starryapi.platform.deepseek.DeepSeekModelCatalog;
import tech.starryapi.platform.gemini.GeminiModelCatalog;
import tech.starryapi.platform.openai.OpenAiModelCatalog;

public final class ProxyModelCatalog {

    private static final List<ModelSpec> MODELS = buildModels();

    private ProxyModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    private static List<ModelSpec> buildModels() {
        return Stream.of(
            AnthropicModelCatalog.models(),
            OpenAiModelCatalog.models(),
            GeminiModelCatalog.models(),
            DeepSeekModelCatalog.models()
        ).flatMap(List::stream).toList();
    }
}
