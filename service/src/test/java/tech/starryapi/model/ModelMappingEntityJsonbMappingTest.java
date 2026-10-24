package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.OutboundRow;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.r2dbc.core.Parameter;

@SuppressWarnings("deprecation") // Spring Data R2DBC's OutboundRow still uses org.springframework.r2dbc.core.Parameter
class ModelMappingEntityJsonbMappingTest {

    @Test
    void writesEachJsonColumnOnceWithThePostgresqlJsonType() {
        ModelMappingEntity entity = new ModelMappingEntity();
        entity.setMappingId("mapping-1");
        entity.setTargetModels("[\"gpt-5\"]");
        entity.setWeights(null);
        entity.setApiKeyIds("[\"key-1\"]");
        OutboundRow row = new OutboundRow();

        converter().write(entity, row);

        // Spring Data 4 quotes column identifiers, so a hand-written callback adding unquoted keys would duplicate them.
        assertThat(row.keySet()).extracting(SqlIdentifier::getReference)
            .doesNotHaveDuplicates()
            .contains("target_models", "weights", "api_key_ids");
        assertJson(parameter(row, "target_models"), "[\"gpt-5\"]");
        assertThat(parameter(row, "weights").hasValue()).isFalse();
        assertThat(parameter(row, "weights").getType()).isEqualTo(Json.class);
        assertJson(parameter(row, "api_key_ids"), "[\"key-1\"]");
    }

    @Test
    void exposesTheJsonColumnsAsText() {
        ModelMappingEntity entity = new ModelMappingEntity();
        entity.setTargetModels("[\"gpt-5\"]");

        assertThat(entity.getTargetModels()).isEqualTo("[\"gpt-5\"]");
        assertThat(entity.getWeights()).isNull();
    }

    private static MappingR2dbcConverter converter() {
        R2dbcCustomConversions conversions = R2dbcCustomConversions.of(PostgresDialect.INSTANCE);
        R2dbcMappingContext context = new R2dbcMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        return new MappingR2dbcConverter(context, conversions);
    }

    private static Parameter parameter(OutboundRow row, String column) {
        return row.entrySet().stream()
            .filter(entry -> entry.getKey().getReference().equals(column))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    }

    private static void assertJson(Parameter parameter, String expected) {
        assertThat(parameter.getType()).isAssignableTo(Json.class);
        assertThat(parameter.getValue())
            .isInstanceOfSatisfying(Json.class, value -> assertThat(value.asString()).isEqualTo(expected));
    }
}
