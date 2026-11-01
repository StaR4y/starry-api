package tech.starryapi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ModelDtos {

    private ModelDtos() {
    }

    public record ModelSpec(
        String id,
        String name,
        String provider,
        String family,
        String ownedBy,
        String description,
        List<String> supportedInputTypes,
        int maxInputTokens,
        int maxOutputTokens,
        String targetModelId,
        String icon,
        String requestFormat,
        String endpoint
    ) {
        public ModelSpec(
            String id,
            String name,
            String provider,
            String family,
            String ownedBy,
            String description,
            List<String> supportedInputTypes,
            int maxInputTokens,
            int maxOutputTokens,
            String targetModelId
        ) {
            this(
                id,
                name,
                provider,
                family,
                ownedBy,
                description,
                supportedInputTypes,
                maxInputTokens,
                maxOutputTokens,
                targetModelId,
                null,
                "OPENAI_CHAT_COMPLETIONS",
                "/v1/chat/completions"
            );
        }

        public ModelSpec {
            supportedInputTypes = supportedInputTypes == null ? List.of() : List.copyOf(supportedInputTypes);
            requestFormat = requestFormat == null || requestFormat.isBlank()
                ? "OPENAI_CHAT_COMPLETIONS"
                : requestFormat;
            endpoint = endpoint == null || endpoint.isBlank() ? "/v1/chat/completions" : endpoint;
        }
    }

    public record ModelListResponse(
        String object,
        List<ModelListItem> data,
        List<ModelListItem> models
    ) {
        public ModelListResponse {
            List<ModelListItem> items = data == null ? models : data;
            data = items == null ? List.of() : List.copyOf(items);
            models = models == null ? data : List.copyOf(models);
        }
    }

    public record ModelListItem(
        String id,
        String object,
        long created,
        @JsonProperty("owned_by") String ownedBy,
        String provider,
        String family,
        String targetModelId,
        String name,
        String modelName,
        String description,
        List<String> supportedInputTypes,
        int maxInputTokens,
        int maxOutputTokens,
        @JsonProperty("context_length") int contextLength,
        @JsonProperty("max_output_tokens") int maxOutputTokensCompatible,
        String billingMode,
        BigDecimal tokensPer100Balance,
        BigDecimal inputTokensPer100Balance,
        BigDecimal outputTokensPer100Balance,
        BigDecimal cacheWriteTokensPer100Balance,
        BigDecimal cacheReadTokensPer100Balance,
        BigDecimal totalPricePerMillionTokens,
        BigDecimal inputPricePerMillionTokens,
        BigDecimal outputPricePerMillionTokens,
        BigDecimal cacheWritePricePerMillionTokens,
        BigDecimal cacheReadPricePerMillionTokens,
        String icon,
        String requestFormat,
        String endpoint,
        List<ModelSourceTier> sourceTiers
    ) {
        public ModelListItem {
            supportedInputTypes = supportedInputTypes == null ? List.of() : List.copyOf(supportedInputTypes);
            sourceTiers = sourceTiers == null || sourceTiers.isEmpty() ? null : List.copyOf(sourceTiers);
        }

        /** A model that is not listed under source tiers: the field is left out of the JSON. */
        public ModelListItem(
            String id,
            String object,
            long created,
            String ownedBy,
            String provider,
            String family,
            String targetModelId,
            String name,
            String modelName,
            String description,
            List<String> supportedInputTypes,
            int maxInputTokens,
            int maxOutputTokens,
            int contextLength,
            int maxOutputTokensCompatible,
            String billingMode,
            BigDecimal tokensPer100Balance,
            BigDecimal inputTokensPer100Balance,
            BigDecimal outputTokensPer100Balance,
            BigDecimal cacheWriteTokensPer100Balance,
            BigDecimal cacheReadTokensPer100Balance,
            BigDecimal totalPricePerMillionTokens,
            BigDecimal inputPricePerMillionTokens,
            BigDecimal outputPricePerMillionTokens,
            BigDecimal cacheWritePricePerMillionTokens,
            BigDecimal cacheReadPricePerMillionTokens,
            String icon,
            String requestFormat,
            String endpoint
        ) {
            this(id, object, created, ownedBy, provider, family, targetModelId, name, modelName, description,
                supportedInputTypes, maxInputTokens, maxOutputTokens, contextLength, maxOutputTokensCompatible,
                billingMode, tokensPer100Balance, inputTokensPer100Balance, outputTokensPer100Balance,
                cacheWriteTokensPer100Balance, cacheReadTokensPer100Balance, totalPricePerMillionTokens,
                inputPricePerMillionTokens, outputPricePerMillionTokens, cacheWritePricePerMillionTokens,
                cacheReadPricePerMillionTokens, icon, requestFormat, endpoint, null);
        }

        /** This model with the tiers its price is listed under (design 5.7); null or empty leaves it as it is. */
        public ModelListItem withSourceTiers(List<ModelSourceTier> tiers) {
            if (tiers == null || tiers.isEmpty()) {
                return this;
            }
            return new ModelListItem(id, object, created, ownedBy, provider, family, targetModelId, name, modelName,
                description, supportedInputTypes, maxInputTokens, maxOutputTokens, contextLength,
                maxOutputTokensCompatible, billingMode, tokensPer100Balance, inputTokensPer100Balance,
                outputTokensPer100Balance, cacheWriteTokensPer100Balance, cacheReadTokensPer100Balance,
                totalPricePerMillionTokens, inputPricePerMillionTokens, outputPricePerMillionTokens,
                cacheWritePricePerMillionTokens, cacheReadPricePerMillionTokens, icon, requestFormat, endpoint, tiers);
        }

        public ModelListItem withPricing(ModelPublicPricing pricing) {
            if (pricing == null) {
                return this;
            }
            return new ModelListItem(
                id,
                object,
                created,
                ownedBy,
                provider,
                family,
                targetModelId,
                name,
                modelName,
                description,
                supportedInputTypes,
                maxInputTokens,
                maxOutputTokens,
                contextLength,
                maxOutputTokensCompatible,
                pricing.billingMode(),
                pricing.tokensPer100Balance(),
                pricing.inputTokensPer100Balance(),
                pricing.outputTokensPer100Balance(),
                pricing.cacheWriteTokensPer100Balance(),
                pricing.cacheReadTokensPer100Balance(),
                pricing.totalPricePerMillionTokens(),
                pricing.inputPricePerMillionTokens(),
                pricing.outputPricePerMillionTokens(),
                pricing.cacheWritePricePerMillionTokens(),
                pricing.cacheReadPricePerMillionTokens(),
                icon,
                requestFormat,
                endpoint,
                sourceTiers
            );
        }
    }

    /**
     * One source tier a model is served from, as the model list shows it when the vendor lists its tiers publicly:
     * {@code id} is the tier's identifier (what a client puts after {@code @} to ask for it), {@code priceFactor} what
     * the standard price is multiplied by, {@code pricing} that price with the factor applied, and {@code selectable}
     * whether a client may ask for the tier. Nothing is said of the accounts or the protocol behind it.
     */
    public record ModelSourceTier(
        String id,
        int level,
        String name,
        BigDecimal priceFactor,
        boolean selectable,
        ModelPublicPricing pricing
    ) {}

    public record UpsertManagedModelRequest(
        @NotBlank @Size(max = 255) String id,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String icon,
        @Size(max = 64) String provider,
        @Size(max = 128) String family,
        @Size(max = 255) String targetModelId,
        @NotBlank @Size(max = 64) String requestFormat,
        @NotBlank @Size(max = 2048) String endpoint,
        @Min(1) @Max(10_000_000) Integer contextWindow,
        @Size(max = 1024) String description,
        Boolean enabled,
        @Size(max = 16) String badgeColor
    ) {
        public UpsertManagedModelRequest(
            String id,
            String name,
            String icon,
            String provider,
            String family,
            String targetModelId,
            String requestFormat,
            String endpoint,
            Integer contextWindow,
            String description,
            Boolean enabled
        ) {
            this(
                id,
                name,
                icon,
                provider,
                family,
                targetModelId,
                requestFormat,
                endpoint,
                contextWindow,
                description,
                enabled,
                null
            );
        }
    }

    public record ManagedModelResponse(
        String id,
        String name,
        String icon,
        String provider,
        String family,
        String targetModelId,
        String requestFormat,
        String endpoint,
        Integer contextWindow,
        String description,
        Boolean enabled,
        Boolean builtIn,
        String badgeColor,
        ModelPublicPricing pricing
    ) {
        public ManagedModelResponse(
            String id,
            String name,
            String icon,
            String provider,
            String family,
            String targetModelId,
            String requestFormat,
            String endpoint,
            Integer contextWindow,
            String description,
            Boolean enabled,
            Boolean builtIn
        ) {
            this(
                id,
                name,
                icon,
                provider,
                family,
                targetModelId,
                requestFormat,
                endpoint,
                contextWindow,
                description,
                enabled,
                builtIn,
                null,
                null
            );
        }

        public ManagedModelResponse(
            String id,
            String name,
            String icon,
            String provider,
            String family,
            String targetModelId,
            String requestFormat,
            String endpoint,
            Integer contextWindow,
            String description,
            Boolean enabled,
            Boolean builtIn,
            ModelPublicPricing pricing
        ) {
            this(
                id,
                name,
                icon,
                provider,
                family,
                targetModelId,
                requestFormat,
                endpoint,
                contextWindow,
                description,
                enabled,
                builtIn,
                null,
                pricing
            );
        }

        public ManagedModelResponse withPricing(ModelPublicPricing pricing) {
            return new ManagedModelResponse(
                id,
                name,
                icon,
                provider,
                family,
                targetModelId,
                requestFormat,
                endpoint,
                contextWindow,
                description,
                enabled,
                builtIn,
                badgeColor,
                pricing
            );
        }
    }

    public record ModelIconUploadResponse(
        String url,
        String fileName,
        long sizeBytes
    ) {
    }

    public record ReorderManagedModelsRequest(
        List<@NotBlank @Size(max = 255) String> modelIds,
        Boolean restoreDefault
    ) {
        public ReorderManagedModelsRequest {
            modelIds = modelIds == null ? List.of() : List.copyOf(modelIds);
        }

        public ReorderManagedModelsRequest(List<String> modelIds) {
            this(modelIds, false);
        }
    }

    public record ModelCatalogDisplaySettings(
        String layout,
        String density,
        Integer columns,
        Boolean showIcon,
        Boolean showFamily,
        Boolean showContext,
        Boolean showOrigin,
        Boolean showDescription,
        String groupBy,
        String cardStyle
    ) {
        public static ModelCatalogDisplaySettings defaults() {
            return new ModelCatalogDisplaySettings(
                "cards",
                "comfortable",
                0,
                true,
                true,
                true,
                true,
                false,
                "provider",
                "default"
            );
        }
    }

    public record UpdateModelCatalogDisplayRequest(
        String layout,
        String density,
        Integer columns,
        Boolean showIcon,
        Boolean showFamily,
        Boolean showContext,
        Boolean showOrigin,
        Boolean showDescription,
        String groupBy,
        String cardStyle,
        Boolean restoreDefault
    ) {
    }

    public record ModelCatalogViewSettings(
        ModelCatalogDisplaySettings display,
        List<String> modelIds
    ) {
        public ModelCatalogViewSettings {
            display = display == null ? ModelCatalogDisplaySettings.defaults() : display;
            modelIds = modelIds == null ? List.of() : List.copyOf(modelIds);
        }
    }

    public record ModelPublicPricing(
        String billingMode,
        BigDecimal tokensPer100Balance,
        BigDecimal inputTokensPer100Balance,
        BigDecimal outputTokensPer100Balance,
        BigDecimal cacheWriteTokensPer100Balance,
        BigDecimal cacheReadTokensPer100Balance,
        BigDecimal totalPricePerMillionTokens,
        BigDecimal inputPricePerMillionTokens,
        BigDecimal outputPricePerMillionTokens,
        BigDecimal cacheWritePricePerMillionTokens,
        BigDecimal cacheReadPricePerMillionTokens
    ) {
        public ModelPublicPricing(
            String billingMode,
            BigDecimal tokensPer100Balance,
            BigDecimal inputTokensPer100Balance,
            BigDecimal outputTokensPer100Balance
        ) {
            this(
                billingMode,
                tokensPer100Balance,
                inputTokensPer100Balance,
                outputTokensPer100Balance,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }

        public ModelPublicPricing(
            String billingMode,
            BigDecimal tokensPer100Balance,
            BigDecimal inputTokensPer100Balance,
            BigDecimal outputTokensPer100Balance,
            BigDecimal cacheWriteTokensPer100Balance,
            BigDecimal cacheReadTokensPer100Balance
        ) {
            this(
                billingMode,
                tokensPer100Balance,
                inputTokensPer100Balance,
                outputTokensPer100Balance,
                cacheWriteTokensPer100Balance,
                cacheReadTokensPer100Balance,
                null,
                null,
                null,
                null,
                null
            );
        }
    }

    public record CreateMappingRequest(
        @NotBlank String name,
        @NotBlank String mappingType,
        @NotBlank String sourceModel,
        @NotEmpty List<String> targetModels,
        List<Integer> weights,
        Integer priority,
        List<String> apiKeyIds
    ) {
    }

    public record UpdateMappingRequest(
        String name,
        Boolean enabled,
        String mappingType,
        String sourceModel,
        List<String> targetModels,
        List<Integer> weights,
        Integer priority,
        List<String> apiKeyIds
    ) {
    }

    public record MappingResponse(
        String mappingId,
        String name,
        Boolean enabled,
        String mappingType,
        String sourceModel,
        List<String> targetModels,
        List<Integer> weights,
        Integer priority,
        List<String> apiKeyIds,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record ModelPricingRuleResponse(
        String ruleId,
        Boolean enabled,
        String modelPattern,
        BigDecimal priceMultiplier,
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier,
        BigDecimal totalMultiplier,
        BigDecimal inputPricePerMillionTokens,
        BigDecimal outputPricePerMillionTokens,
        BigDecimal cacheWritePricePerMillionTokens,
        BigDecimal cacheReadPricePerMillionTokens,
        BigDecimal totalPricePerMillionTokens,
        Integer priority,
        Instant createdAt,
        Instant updatedAt,
        String scope,
        String provider,
        String family
    ) {
        public ModelPricingRuleResponse(
            String ruleId, Boolean enabled, String modelPattern, BigDecimal priceMultiplier,
            BigDecimal inputMultiplier, BigDecimal outputMultiplier, BigDecimal cacheWriteMultiplier,
            BigDecimal cacheReadMultiplier, BigDecimal totalMultiplier, BigDecimal inputPricePerMillionTokens,
            BigDecimal outputPricePerMillionTokens, BigDecimal cacheWritePricePerMillionTokens,
            BigDecimal cacheReadPricePerMillionTokens, BigDecimal totalPricePerMillionTokens, Integer priority,
            Instant createdAt, Instant updatedAt
        ) {
            this(ruleId, enabled, modelPattern, priceMultiplier, inputMultiplier, outputMultiplier,
                cacheWriteMultiplier, cacheReadMultiplier, totalMultiplier, inputPricePerMillionTokens,
                outputPricePerMillionTokens, cacheWritePricePerMillionTokens, cacheReadPricePerMillionTokens,
                totalPricePerMillionTokens, priority, createdAt, updatedAt, "model", null, null);
        }
    }

    public record ModelPricingResponse(
        List<ModelPricingRuleResponse> rules,
        ModelPricingSourceStatus source
    ) {
        public ModelPricingResponse {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        public ModelPricingResponse(List<ModelPricingRuleResponse> rules) {
            this(rules, null);
        }
    }

    /**
     * {@code factors} are source-tier price factors (each above 0 and at most 1000, at most 9) to price the model at as
     * well: the rule that applies is found once, and the factors multiply its result (design 5.1).
     */
    public record ModelPricingPreviewRequest(
        @NotBlank String model,
        List<UpdateModelPricingRuleRequest> rules,
        List<BigDecimal> factors
    ) {
        public ModelPricingPreviewRequest {
            rules = rules == null ? List.of() : List.copyOf(rules);
            factors = factors == null ? List.of() : List.copyOf(factors);
        }

        public ModelPricingPreviewRequest(String model, List<UpdateModelPricingRuleRequest> rules) {
            this(model, rules, null);
        }
    }

    /** {@code factorPricing} answers {@code factors}, in the same order, and is left out when none was asked for. */
    public record ModelPricingPreview(
        String model, String pricingModel, String source, String matchedPriceModel,
        BigDecimal multiplier, List<String> matchedRuleIds,
        ModelPublicPricing basePricing, ModelPublicPricing pricing,
        List<FactorPricing> factorPricing
    ) {
        public ModelPricingPreview(
            String model, String pricingModel, String source, String matchedPriceModel,
            BigDecimal multiplier, List<String> matchedRuleIds,
            ModelPublicPricing basePricing, ModelPublicPricing pricing
        ) {
            this(model, pricingModel, source, matchedPriceModel, multiplier, matchedRuleIds, basePricing, pricing, null);
        }
    }

    /** The price a preview gives when a source-tier {@code factor} is applied to it. */
    public record FactorPricing(BigDecimal factor, ModelPublicPricing pricing) {}

    public record ModelPricingSourceStatus(
        Boolean remoteRefreshEnabled,
        Boolean syncCatalogIds,
        String sourceUrl,
        String checksumUrl,
        String refreshInterval,
        Integer catalogEntries,
        Instant refreshedAt,
        String lastError
    ) {
    }

    public record UpdateModelPricingSourceRequest(
        Boolean remoteRefreshEnabled,
        Boolean syncCatalogIds,
        String sourceUrl,
        String checksumUrl,
        Boolean refreshNow
    ) {
    }

    public record UpdateModelPricingRuleRequest(
        String ruleId,
        Boolean enabled,
        String modelPattern,
        BigDecimal priceMultiplier,
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier,
        BigDecimal totalMultiplier,
        BigDecimal inputPricePerMillionTokens,
        BigDecimal outputPricePerMillionTokens,
        BigDecimal cacheWritePricePerMillionTokens,
        BigDecimal cacheReadPricePerMillionTokens,
        BigDecimal totalPricePerMillionTokens,
        Integer priority,
        String scope,
        String provider,
        String family
    ) {
        public UpdateModelPricingRuleRequest(
            String ruleId, Boolean enabled, String modelPattern, BigDecimal priceMultiplier,
            BigDecimal inputMultiplier, BigDecimal outputMultiplier, BigDecimal cacheWriteMultiplier,
            BigDecimal cacheReadMultiplier, BigDecimal totalMultiplier, BigDecimal inputPricePerMillionTokens,
            BigDecimal outputPricePerMillionTokens, BigDecimal cacheWritePricePerMillionTokens,
            BigDecimal cacheReadPricePerMillionTokens, BigDecimal totalPricePerMillionTokens, Integer priority
        ) {
            this(ruleId, enabled, modelPattern, priceMultiplier, inputMultiplier, outputMultiplier,
                cacheWriteMultiplier, cacheReadMultiplier, totalMultiplier, inputPricePerMillionTokens,
                outputPricePerMillionTokens, cacheWritePricePerMillionTokens, cacheReadPricePerMillionTokens,
                totalPricePerMillionTokens, priority, "model", null, null);
        }

        public UpdateModelPricingRuleRequest(
            String ruleId,
            Boolean enabled,
            String modelPattern,
            BigDecimal priceMultiplier,
            Integer priority
        ) {
            this(
                ruleId,
                enabled,
                modelPattern,
                priceMultiplier,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                priority
            );
        }

        public UpdateModelPricingRuleRequest(
            String ruleId,
            Boolean enabled,
            String modelPattern,
            BigDecimal inputMultiplier,
            BigDecimal outputMultiplier,
            BigDecimal cacheWriteMultiplier,
            BigDecimal cacheReadMultiplier,
            BigDecimal totalMultiplier,
            BigDecimal inputPricePerMillionTokens,
            BigDecimal outputPricePerMillionTokens,
            BigDecimal cacheWritePricePerMillionTokens,
            BigDecimal cacheReadPricePerMillionTokens,
            BigDecimal totalPricePerMillionTokens,
            Integer priority
        ) {
            this(
                ruleId,
                enabled,
                modelPattern,
                null,
                inputMultiplier,
                outputMultiplier,
                cacheWriteMultiplier,
                cacheReadMultiplier,
                totalMultiplier,
                inputPricePerMillionTokens,
                outputPricePerMillionTokens,
                cacheWritePricePerMillionTokens,
                cacheReadPricePerMillionTokens,
                totalPricePerMillionTokens,
                priority
            );
        }

        public UpdateModelPricingRuleRequest(
            String ruleId,
            Boolean enabled,
            String modelPattern,
            BigDecimal inputMultiplier,
            BigDecimal outputMultiplier,
            BigDecimal totalMultiplier,
            BigDecimal inputPricePerMillionTokens,
            BigDecimal outputPricePerMillionTokens,
            BigDecimal totalPricePerMillionTokens,
            Integer priority
        ) {
            this(
                ruleId,
                enabled,
                modelPattern,
                null,
                inputMultiplier,
                outputMultiplier,
                null,
                null,
                totalMultiplier,
                inputPricePerMillionTokens,
                outputPricePerMillionTokens,
                null,
                null,
                totalPricePerMillionTokens,
                priority
            );
        }
    }

    public record UpdateModelPricingRequest(
        List<UpdateModelPricingRuleRequest> rules
    ) {
        public UpdateModelPricingRequest {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
