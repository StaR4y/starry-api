package xyz.star4y.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
        String targetModelId
    ) {
        public ModelSpec {
            supportedInputTypes = supportedInputTypes == null ? List.of() : List.copyOf(supportedInputTypes);
        }
    }

    public record ModelListResponse(
        String object,
        List<ModelListItem> data
    ) {
        public ModelListResponse {
            data = data == null ? List.of() : List.copyOf(data);
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
        @JsonProperty("max_output_tokens") int maxOutputTokensCompatible
    ) {
        public ModelListItem {
            supportedInputTypes = supportedInputTypes == null ? List.of() : List.copyOf(supportedInputTypes);
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
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal totalMultiplier,
        Integer priority,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record ModelPricingResponse(
        List<ModelPricingRuleResponse> rules
    ) {
        public ModelPricingResponse {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record UpdateModelPricingRuleRequest(
        String ruleId,
        Boolean enabled,
        @NotBlank String modelPattern,
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal totalMultiplier,
        Integer priority
    ) {
    }

    public record UpdateModelPricingRequest(
        List<UpdateModelPricingRuleRequest> rules
    ) {
        public UpdateModelPricingRequest {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
