package tech.starryapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Starry API")
                .version("0.1.0")
                .description("OpenAI, Anthropic, and Gemini compatible multi-platform model routing service")
                .license(new License().name("AGPL-3.0-only").url("https://www.gnu.org/licenses/agpl-3.0.html")));
    }

    /**
     * swagger-core introspects types with Jackson 2, which it knows as "any JSON value" for its own {@code JsonNode}
     * (documented as {@code {}}). The Jackson 3 {@code JsonNode} that the proxy endpoints take is only a bean to it and
     * would be listed as an object with {@code number}, {@code nodeType} and similar properties.
     */
    @Bean
    OpenApiCustomizer jsonNodeIsAnyJsonValue() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().computeIfPresent("JsonNode", (name, schema) -> new Schema<>());
            }
        };
    }
}
