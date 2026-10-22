package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.jackson.JacksonEndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.jackson.EndpointJsonMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.apikey.ApiKeyDtos;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The proxy passes {@code JsonNode} trees through WebFlux and WebClient, and every response DTO goes through the same
 * codecs. Both tests run Boot's auto-configuration against the real application.yml and {@link JacksonConfig}, and
 * assert on what the application's own settings change (omitted nulls, declaration order, ISO instants), so they fail
 * if a codec stops using the application's JsonMapper and falls back to Spring Framework's default one.
 */
class JsonCodecWiringTest {

    private static final ObjectMapper MAPPER = JacksonConfig.newMapper();

    private static final ApiKeyDtos.CreatedApiKeyResponse CREATED = new ApiKeyDtos.CreatedApiKeyResponse(
        "key-1", "user-1", "CI", null, "sk-star", null, null, null, null, Instant.parse("2026-10-23T06:24:25Z"));
    private static final String CREATED_JSON =
        "{\"keyId\":\"key-1\",\"userId\":\"user-1\",\"name\":\"CI\",\"keyPrefix\":\"sk-star\",\"createdAt\":\"2026-10-23T06:24:25Z\"}";

    @Test
    void upstreamWebClientSendsAndReadsJsonNodesWithTheApplicationMapper() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.set(new String(body, StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, CodecsAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class, HttpClientConfig.class)
                .withBean(ProxyProperties.class, ProxyProperties::new)
                .run(context -> {
                    WebClient client = context.getBean("upstreamWebClient", WebClient.class);
                    String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
                    ObjectNode request = MAPPER.createObjectNode().put("model", "gpt-5").put("text", "你好");
                    request.putObject("nested").put("z", 1).put("a", 2);

                    JsonNode response = client.post().uri(uri).bodyValue(request).retrieve().bodyToMono(JsonNode.class).block();

                    assertThat(MAPPER.readTree(received.get())).isEqualTo(request);
                    assertThat(received.get()).isEqualTo("{\"model\":\"gpt-5\",\"text\":\"你好\",\"nested\":{\"z\":1,\"a\":2}}");
                    assertThat(response).isEqualTo(request);

                    // a DTO shows which mapper the client really uses: only the application's omits nulls and keeps declaration order
                    client.post().uri(uri).bodyValue(CREATED).retrieve().toBodilessEntity().block();
                    assertThat(received.get()).isEqualTo(CREATED_JSON);

                    // responses bigger than Spring's default 256 KB buffer limit are still read
                    String large = "x".repeat(300_000);
                    JsonNode echoed = client.post().uri(uri).bodyValue(MAPPER.createObjectNode().put("big", large))
                        .retrieve().bodyToMono(JsonNode.class).block();
                    assertThat(echoed.path("big").asString("")).hasSize(large.length());
                });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webFluxServerReadsAndWritesJsonNodesWithTheApplicationMapper() {
        new ReactiveWebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                CodecsAutoConfiguration.class,
                HttpHandlerAutoConfiguration.class,
                WebFluxAutoConfiguration.class
            ))
            .withUserConfiguration(JacksonConfig.class, EchoConfiguration.class)
            .run(context -> {
                WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                byte[] body = client.post().uri("/echo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult()
                    .getResponseBody();

                assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(
                    "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],\"seen\":true}");

                String dto = new String(client.get().uri("/dto").exchange().expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
                assertThat(dto).isEqualTo(CREATED_JSON);
            });
    }

    @Test
    void actuatorEndpointsShareTheApplicationMapper() {
        // Boot's isolated endpoint mapper has Jackson 3's defaults and would put /actuator/health's members in alphabetical order
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, JacksonEndpointAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class)
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EndpointJsonMapper.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class EchoConfiguration {

        @Bean
        EchoController echoController() {
            return new EchoController();
        }
    }

    @RestController
    static class EchoController {

        @PostMapping("/echo")
        Mono<JsonNode> echo(@RequestBody JsonNode request) {
            return Mono.just(((ObjectNode) request).put("seen", true));
        }

        @GetMapping("/dto")
        Mono<ApiKeyDtos.CreatedApiKeyResponse> dto() {
            return Mono.just(CREATED);
        }
    }
}
