package xyz.star4y.api.platform.catalog;

import java.util.ArrayList;
import java.util.List;
import xyz.star4y.api.model.ModelDtos.ModelSpec;
import xyz.star4y.api.platform.anthropic.AnthropicModelCatalog;
import xyz.star4y.api.platform.gemini.GeminiModelCatalog;
import xyz.star4y.api.platform.openai.OpenAiModelCatalog;

public final class ProxyModelCatalog {

    private static final List<ModelSpec> MODELS = buildModels();

    private ProxyModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    private static List<ModelSpec> buildModels() {
        List<ModelSpec> models = new ArrayList<>();
        models.addAll(AnthropicModelCatalog.models());
        models.addAll(OpenAiModelCatalog.models());
        models.addAll(GeminiModelCatalog.models());
        return List.copyOf(models);
    }
}
