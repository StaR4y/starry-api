package tech.starryapi.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that decides how the application reads and writes JSON.
 *
 * <p>Jackson 3 changed several defaults. The console, the OpenAI/Anthropic SDKs and the CLIs built on them depend on the
 * JSON this service has always produced and accepted, so each default that would change what goes over the wire is put
 * back here instead of being left to whatever the library ships. {@code WireFormatTest}, {@code ProxyWireFormatTest}
 * and {@code JsonNodeWireFormatTest} pin the resulting output; if one of them fails after a Jackson upgrade, fix it
 * here rather than in the test.
 *
 * <p>The one setting that lives in application.yml instead is {@code spring.jackson.default-property-inclusion}
 * (omit nulls).
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /**
     * Runs after Spring Boot's own customizer (order 0), so these settings win over its defaults and over
     * {@code spring.jackson.*}.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    JsonMapperBuilderCustomizer starryJsonMapperCustomizer() {
        return JacksonConfig::configure;
    }

    /**
     * A mapper for code that cannot take the Spring bean (static helpers) with the same read and write behavior as the
     * injected one, apart from the property inclusion that application.yml sets for the bean.
     */
    public static JsonMapper newMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        configure(builder);
        return builder.build();
    }

    static void configure(JsonMapper.Builder builder) {
        builder
            // Jackson 3 sorts bean properties alphabetically; Jackson 2 kept declaration order, which is the order
            // every DTO response has always had on the wire.
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            // Enums travel as their constant name. Jackson 3 would switch to toString() for any enum that overrides it.
            .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING, EnumFeature.READ_ENUMS_USING_TO_STRING)
            // Clients routinely send null (or leave out) numbers and flags on request DTOs with primitive components;
            // those have always read as 0 / false instead of being rejected with a 400.
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            // Extra members and anything after the first JSON value in a body have always been ignored. The proxy
            // forwards bodies from many SDK versions, so strictness here would turn working clients into 400s.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // Instants and durations are ISO-8601 strings, never numbers. Stated explicitly because the default is
            // what every timestamp in every response depends on.
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }
}
