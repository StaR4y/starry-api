package tech.starryapi.model;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.r2dbc.mapping.OutboundRow;
import org.springframework.data.r2dbc.mapping.event.BeforeSaveCallback;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class ModelMappingJsonbWriteCallback implements BeforeSaveCallback<ModelMappingEntity> {

    @Override
    public Mono<ModelMappingEntity> onBeforeSave(
        ModelMappingEntity entity,
        OutboundRow row,
        SqlIdentifier table
    ) {
        row.put("target_models", json(entity.getTargetModels()));
        row.put("weights", json(entity.getWeights()));
        row.put("api_key_ids", json(entity.getApiKeyIds()));
        return Mono.just(entity);
    }

    private Parameter json(String value) {
        return value == null ? Parameter.empty(Json.class) : Parameter.from(Json.of(value));
    }
}
