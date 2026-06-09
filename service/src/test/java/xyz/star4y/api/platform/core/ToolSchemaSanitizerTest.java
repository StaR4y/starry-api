package xyz.star4y.api.platform.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolSchemaSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesPagePropertyButRemovesUnsupportedValidationKeywords() throws Exception {
        JsonNode input = objectMapper.readTree("""
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "OBJECT",
              "title": "Browser screenshot",
              "properties": {
                "page": {
                  "type": "INTEGER",
                  "description": "Page number",
                  "minimum": 0,
                  "default": 0,
                  "format": "int32"
                },
                "mode": {
                  "type": "string",
                  "enum": ["full", "viewport"],
                  "default": "viewport"
                }
              },
              "required": ["page", "missing"]
            }
            """);

        JsonNode schema = ToolSchemaSanitizer.sanitizeParameters(objectMapper, input);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.has("$schema")).isFalse();
        assertThat(schema.has("title")).isFalse();
        JsonNode page = schema.path("properties").path("page");
        assertThat(page.path("type").asText()).isEqualTo("integer");
        assertThat(page.path("description").asText()).isEqualTo("Page number");
        assertThat(page.has("minimum")).isFalse();
        assertThat(page.has("default")).isFalse();
        assertThat(page.has("format")).isFalse();
        assertThat(schema.path("required")).hasSize(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("page");
        assertThat(schema.path("properties").path("mode").path("enum")).hasSize(2);
        assertThat(schema.path("properties").path("mode").has("default")).isFalse();
    }
}
