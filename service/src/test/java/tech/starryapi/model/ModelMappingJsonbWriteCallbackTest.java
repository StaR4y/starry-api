package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.mapping.OutboundRow;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.r2dbc.core.Parameter;

class ModelMappingJsonbWriteCallbackTest {

    @Test
    void bindsJsonColumnsWithThePostgresqlJsonCodec() {
        ModelMappingEntity entity = new ModelMappingEntity();
        entity.setTargetModels("[\"gpt-5\"]");
        entity.setWeights(null);
        entity.setApiKeyIds("[\"key-1\"]");
        OutboundRow row = new OutboundRow();

        new ModelMappingJsonbWriteCallback()
            .onBeforeSave(entity, row, SqlIdentifier.unquoted("model_mappings"))
            .block();

        assertJson(row.get("target_models"), "[\"gpt-5\"]");
        assertThat(row.get("weights").hasValue()).isFalse();
        assertThat(row.get("weights").getType()).isEqualTo(Json.class);
        assertJson(row.get("api_key_ids"), "[\"key-1\"]");
    }

    private void assertJson(Parameter parameter, String expected) {
        assertThat(parameter.getType()).isAssignableTo(Json.class);
        assertThat(parameter.getValue())
            .isInstanceOfSatisfying(Json.class, value -> assertThat(value.asString()).isEqualTo(expected));
    }
}
