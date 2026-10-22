package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    @Test
    void jsonNodeIsDocumentedAsAnyJsonValueNotAsABean() {
        Schema<?> introspected = new Schema<>().type("object").addProperty("nodeType", new Schema<>().type("string"));
        Schema<?> other = new Schema<>().type("object");
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("JsonNode", introspected).addSchemas("ApiError", other));

        new OpenApiConfig().jsonNodeIsAnyJsonValue().customise(openApi);

        assertThat(openApi.getComponents().getSchemas().get("JsonNode")).isEqualTo(new Schema<>());
        assertThat(openApi.getComponents().getSchemas().get("ApiError")).isSameAs(other);
        assertThat(openApi.getComponents().getSchemas().keySet()).containsExactly("JsonNode", "ApiError");
    }

    @Test
    void documentsWithoutComponentsAreLeftAlone() {
        OpenAPI openApi = new OpenAPI();

        new OpenApiConfig().jsonNodeIsAnyJsonValue().customise(openApi);

        assertThat(openApi.getComponents()).isNull();
    }
}
