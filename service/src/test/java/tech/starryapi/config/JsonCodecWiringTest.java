package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Spring Framework 7 defaults to Jackson 3 codecs, but the application still passes Jackson 2 JsonNode/ObjectNode
 * through WebFlux and WebClient. Both tests run Boot's auto-configuration against the real application.yml, so they
 * fail if spring.http.codecs.preferred-json-mapper is removed or stops working.
 */
@SuppressWarnings("removal") // Jackson2AutoConfiguration is the Boot 4 bridge for the application's Jackson 2 ObjectMapper
class JsonCodecWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void upstreamWebClientSendsAndReadsJackson2JsonNodes() throws Exception {
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
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class, CodecsAutoConfiguration.class))
                .withUserConfiguration(HttpClientConfig.class)
                .withBean(ProxyProperties.class, ProxyProperties::new)
                .run(context -> {
                    WebClient client = context.getBean("upstreamWebClient", WebClient.class);
                    ObjectNode request = MAPPER.createObjectNode().put("model", "gpt-5").put("text", "你好");

                    JsonNode response = client.post()
                        .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/echo")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();

                    assertThat(MAPPER.readTree(received.get())).isEqualTo(request);
                    assertThat(response).isEqualTo(request);
                });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webFluxServerReadsAndWritesJackson2JsonNodes() {
        new ReactiveWebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                Jackson2AutoConfiguration.class,
                CodecsAutoConfiguration.class,
                HttpHandlerAutoConfiguration.class,
                WebFluxAutoConfiguration.class
            ))
            .withUserConfiguration(EchoConfiguration.class)
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

                assertThat(MAPPER.readTree(body)).isEqualTo(MAPPER.readTree(
                    "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],\"seen\":true}"));
            });
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
    }
}
