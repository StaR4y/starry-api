package tech.starryapi.model;

import io.r2dbc.postgresql.codec.Json;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("model_mappings")
public class ModelMappingEntity {

    @Id
    private Long id;
    private String mappingId;
    private String name;
    private Boolean enabled;
    private String mappingType;
    private String sourceModel;
    // jsonb columns. Spring Data maps the driver's Json type natively (a String property would be bound as text and
    // rejected by PostgreSQL); the accessors below keep the JSON text API the rest of the code uses.
    private Json targetModels;
    private Json weights;
    private Integer priority;
    private Json apiKeyIds;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMappingId() {
        return mappingId;
    }

    public void setMappingId(String mappingId) {
        this.mappingId = mappingId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getMappingType() {
        return mappingType;
    }

    public void setMappingType(String mappingType) {
        this.mappingType = mappingType;
    }

    public String getSourceModel() {
        return sourceModel;
    }

    public void setSourceModel(String sourceModel) {
        this.sourceModel = sourceModel;
    }

    public String getTargetModels() {
        return text(targetModels);
    }

    public void setTargetModels(String targetModels) {
        this.targetModels = json(targetModels);
    }

    public String getWeights() {
        return text(weights);
    }

    public void setWeights(String weights) {
        this.weights = json(weights);
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getApiKeyIds() {
        return text(apiKeyIds);
    }

    public void setApiKeyIds(String apiKeyIds) {
        this.apiKeyIds = json(apiKeyIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    private static String text(Json value) {
        return value == null ? null : value.asString();
    }

    private static Json json(String value) {
        return value == null ? null : Json.of(value);
    }
}
