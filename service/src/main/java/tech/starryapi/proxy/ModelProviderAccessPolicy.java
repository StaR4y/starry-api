package tech.starryapi.proxy;

import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport;

final class ModelProviderAccessPolicy {

    private static final String ALL_PROVIDERS = "*";

    private ModelProviderAccessPolicy() {
    }

    static void verify(
        ModelCatalogProvider catalog,
        ApiKeyPrincipal principal,
        String requestedModel,
        String mappedModel
    ) {
        String allowedProvider = principal == null ? ALL_PROVIDERS : principal.modelProvider();
        if (allowedProvider == null || allowedProvider.isBlank() || ALL_PROVIDERS.equals(allowedProvider)) {
            return;
        }
        requireProvider(catalog, requestedModel, allowedProvider, false);
        if (mappedModel != null && !mappedModel.equalsIgnoreCase(requestedModel)) {
            requireProvider(catalog, mappedModel, allowedProvider, true);
        }
    }

    private static void requireProvider(
        ModelCatalogProvider catalog,
        String modelId,
        String allowedProvider,
        boolean mapped
    ) {
        ModelDtos.ModelSpec model = findModel(catalog, modelId).orElseThrow(() -> new ApiException(
            HttpStatus.BAD_REQUEST,
            mapped ? "MAPPED_MODEL_NOT_AVAILABLE" : "MODEL_NOT_AVAILABLE",
            mapped
                ? "Mapped model does not exist in the enabled model catalog"
                : "Requested model does not exist in the enabled model catalog"
        ));
        if (!PlatformModelCatalogSupport.sameCatalogProvider(allowedProvider, model.provider())) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "API_KEY_MODEL_PROVIDER_FORBIDDEN",
                "This API key can only call models in provider " + allowedProvider
            );
        }
    }

    private static Optional<ModelDtos.ModelSpec> findModel(ModelCatalogProvider catalog, String modelId) {
        String normalized = normalizeModelId(modelId);
        return catalog.models().stream()
            .filter(model -> normalizeModelId(model.id()).equals(normalized))
            .findFirst();
    }

    private static String normalizeModelId(String modelId) {
        String normalized = modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("starry/")) {
            normalized = normalized.substring("starry/".length());
        }
        if (normalized.endsWith("-fast")) {
            normalized = normalized.substring(0, normalized.length() - "-fast".length());
        }
        return normalized;
    }
}
