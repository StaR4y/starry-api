package tech.starryapi.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tech.starryapi.platform.catalog.ProxyModelCatalog;

@Service
public class DefaultModelCatalogProvider implements ModelCatalogProvider {

    private final List<ModelDtos.ModelSpec> models = ProxyModelCatalog.models();
    private final Map<String, String> targetById = models.stream()
        .collect(Collectors.toUnmodifiableMap(spec -> key(spec.id()), ModelDtos.ModelSpec::targetModelId));

    @Override
    public List<ModelDtos.ModelSpec> models() {
        return models;
    }

    @Override
    public Optional<String> targetFor(String modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(targetById.get(key(modelId)));
    }

    private String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
