package tech.starryapi.config;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON stack exactly as production wires it: Boot's JSON auto-configuration and codec customizers driven by the
 * real application.yml, a WebFlux server whose codecs come from them, and the application's own
 * {@code upstreamWebClient}. Tests use it to look at the bytes clients and upstreams actually see, so they stay free of
 * Jackson types: only this class knows which Jackson generation is in use.
 */
public final class ProductionJson {

    private static final Map<String, Object> RESULTS = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    private ProductionJson() {
    }

    /** The application's ObjectMapper bean, for tests that only need to read or write JSON the way production does. */
    public static ObjectMapper mapper() {
        AtomicReference<ObjectMapper> mapper = new AtomicReference<>();
        run(session -> mapper.set(session.mapper()));
        return mapper.get();
    }

    public static void run(ThrowingConsumer<Session> test) {
        new ReactiveWebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                CodecsAutoConfiguration.class,
                HttpHandlerAutoConfiguration.class,
                WebFluxAutoConfiguration.class
            ))
            .withUserConfiguration(JacksonConfig.class, HttpClientConfig.class, Routes.class)
            .withBean(ProxyProperties.class, ProxyProperties::new)
            .run(context -> {
                if (context.getStartupFailure() != null) {
                    throw new AssertionError("JSON test context failed to start", context.getStartupFailure());
                }
                test.accept(new Session(
                    context.getBean(ObjectMapper.class),
                    WebTestClient.bindToApplicationContext(context).build(),
                    context.getBean("upstreamWebClient", WebClient.class)
                ));
            });
    }

    /** What went to an upstream and what came back, as seen through the application's own WebClient. */
    public record Exchange(String sentOnWire, String receivedTree) {
    }

    public static final class Session {

        private final ObjectMapper mapper;
        private final WebTestClient server;
        private final WebClient upstream;

        private Session(ObjectMapper mapper, WebTestClient server, WebClient upstream) {
            this.mapper = mapper;
            this.server = server;
            this.upstream = upstream;
        }

        /** The application's ObjectMapper bean, the one injected into services. */
        public ObjectMapper mapper() {
            return mapper;
        }

        /** The JSON body a client gets when a controller returns {@code value}, produced by the WebFlux server codec. */
        public String write(Object value) {
            String id = store(value);
            byte[] body = server.get().uri("/test/write/" + id)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().returnResult().getResponseBody();
            RESULTS.remove(id);
            return new String(body, StandardCharsets.UTF_8);
        }

        /** The same value serialized by the injected mapper, as services do before storing or embedding JSON. */
        public String writeWithMapper(Object value) throws Exception {
            return mapper.writeValueAsString(value);
        }

        /** What a controller with {@code @RequestBody type} receives for this request body ({@code toString()} of it). */
        public String read(String json, Class<?> type) {
            var result = server.post().uri("/test/read/" + type.getName())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .returnResult(String.class);
            if (result.getStatus().value() != 200) {
                return "HTTP " + result.getStatus().value();
            }
            return new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        }

        /** Same as {@link #read} but through the injected mapper; failures are reported by exception class name. */
        public String readWithMapper(String json, Class<?> type) {
            try {
                return String.valueOf(mapper.readValue(json, type));
            } catch (Exception failure) {
                return "FAIL " + failure.getClass().getSimpleName();
            }
        }

        /** A request body read as JsonNode and written back by the WebFlux server codecs, as the proxy endpoints do. */
        public String echoTree(String json) {
            var result = server.post().uri("/test/echo-tree")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .returnResult(String.class);
            if (result.getStatus().value() != 200) {
                return "HTTP " + result.getStatus().value();
            }
            return new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        }

        /**
         * Sends {@code requestJson} (parsed to a JsonNode first, like the proxy does) through {@code upstreamWebClient} to a
         * local server that records the raw request body and answers with {@code responseJson}.
         */
        public Exchange upstream(String requestJson, String responseJson) throws Exception {
            AtomicReference<String> received = new AtomicReference<>();
            HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fake.createContext("/", exchange -> {
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] reply = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            fake.start();
            try {
                JsonNode response = upstream.post()
                    .uri("http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/echo")
                    .bodyValue(mapper.readTree(requestJson))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
                return new Exchange(received.get(), mapper.writeValueAsString(response));
            } finally {
                fake.stop(0);
            }
        }

        private static String store(Object value) {
            String id = Integer.toString(IDS.incrementAndGet());
            RESULTS.put(id, value);
            return id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Routes {

        @Bean
        RouterFunction<ServerResponse> testRoutes() {
            return RouterFunctions.route()
                .GET("/test/write/{id}", request -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(RESULTS.get(request.pathVariable("id"))))
                .POST("/test/read/{type}", request -> {
                    Class<?> type;
                    try {
                        type = Class.forName(request.pathVariable("type"));
                    } catch (ClassNotFoundException missing) {
                        throw new IllegalStateException(missing);
                    }
                    return request.bodyToMono(type)
                        .map(String::valueOf)
                        .flatMap(text -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).bodyValue(text));
                })
                .POST("/test/echo-tree", request -> request.bodyToMono(JsonNode.class)
                    .flatMap(tree -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(tree)))
                .build();
        }
    }
}
