package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.platform.openai.OpenAiRelayModelsClient.RelayModels;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * The list of a relay's models (design 7.4), read from a relay on the loopback address: where the request goes and what
 * it carries, the names and the windows that are taken from lists of the shapes relays write, and what a failure is.
 */
class OpenAiRelayModelsClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String LEAK = "relay.internal.example.com sk-secret-0123";

    private final List<AutoCloseable> open = new ArrayList<>();

    @BeforeAll
    static void startTheHttpStack() throws Exception {
        try (Relay relay = new Relay((exchange, path) -> answer(exchange, 200, "{\"data\":[]}"))) {
            client().list(account(relay.url())).block(WAIT);
        }
    }

    @AfterEach
    void closeTheRelays() throws Exception {
        for (AutoCloseable relay : open) {
            relay.close();
        }
    }

    @Test
    void asksOnceWithTheKeyOfTheAccountAndNothingElseOfTheClient() throws Exception {
        Relay relay = relay((exchange, path) -> answer(exchange, 200, "{\"data\":[{\"id\":\"relay/sonnet\"}]}"));

        RelayModels models = client().list(account(relay.url())).block(WAIT);

        assertThat(models.models()).extracting(OpenAiRelayModelsClient.RelayModel::id).containsExactly("relay/sonnet");
        assertThat(relay.paths()).containsExactly("GET /v1/models");
        assertThat(relay.headers().getFirst()).containsEntry("authorization", "Bearer sk-test").containsEntry("accept", "application/json");
        assertThat(relay.headers().getFirst().keySet())
            .isSubsetOf("accept", "accept-encoding", "authorization", "connection", "content-length", "host", "user-agent");
    }

    @Test
    void theEndpointFollowsTheBaseUrlOfTheAccount() throws Exception {
        Relay relay = relay((exchange, path) -> answer(exchange, 200, "{\"data\":[]}"));

        for (String base : List.of("", "/v1", "/v1/", "/gateway/v2")) {
            client().list(account(relay.url() + base)).block(WAIT);
        }

        assertThat(relay.paths()).containsExactly("GET /v1/models", "GET /v1/models", "GET /v1/models", "GET /gateway/v2/models");
    }

    @Test
    void takesTheWindowFromWhicheverFieldTheRelayWrites() throws Exception {
        String body = """
            {"object":"list","data":[
              {"id":"openrouter/claude","context_length":200000},
              {"id":"vllm/model","max_model_len":32768},
              {"id":"litellm/model","max_input_tokens":128000},
              {"id":"window/model","context_window":65536},
              {"id":"google/model","inputTokenLimit":1048576},
              {"id":"nested/model","top_provider":{"context_length":100000}},
              {"id":"first/wins","context_length":4096,"max_model_len":8192},
              {"id":"none/model"},
              {"id":"text/model","context_length":"4096"},
              {"id":"zero/model","context_length":0},
              {"id":"huge/model","context_length":99999999999},
              {"id":"fraction/model","context_length":8192.5}
            ]}""";
        Relay relay = relay((exchange, path) -> answer(exchange, 200, body));

        RelayModels models = client().list(account(relay.url())).block(WAIT);

        assertThat(models.truncated()).isFalse();
        assertThat(models.models()).extracting(OpenAiRelayModelsClient.RelayModel::id, OpenAiRelayModelsClient.RelayModel::contextLength).containsExactly(
            tuple("openrouter/claude", 200000),
            tuple("vllm/model", 32768),
            tuple("litellm/model", 128000),
            tuple("window/model", 65536),
            tuple("google/model", 1048576),
            tuple("nested/model", 100000),
            tuple("first/wins", 4096),
            tuple("none/model", null),
            tuple("text/model", null),
            tuple("zero/model", null),
            tuple("huge/model", null),
            tuple("fraction/model", null));
    }

    @Test
    void keepsOnlyNamesAMappingCanStoreOnceEachInTheOrderOfTheRelay() {
        byte[] body = ("{\"data\":[{\"id\":\"b-model\"},{\"id\":\"a-model\"},{\"id\":\"b-model\"},{\"id\":\" padded \"},{\"id\":\"has space\"},"
            + "{\"id\":\"-leading\"},{\"id\":\"\"},{\"id\":5},{\"name\":\"by-name\"},{\"model\":\"by-model\"},\"plain-string\",42,null,"
            + "{\"id\":\"é\"},{\"id\":\"ok.v1:tag/x@y+z\"}]}").getBytes(StandardCharsets.UTF_8);

        RelayModels models = OpenAiRelayModelsClient.parse(JSON, body);

        assertThat(models.models()).extracting(OpenAiRelayModelsClient.RelayModel::id)
            .containsExactly("b-model", "a-model", "padded", "by-name", "by-model", "plain-string", "ok.v1:tag/x@y+z");
    }

    @Test
    void understandsTheOtherShapesOfAList() {
        assertThat(OpenAiRelayModelsClient.parse(JSON, "[{\"id\":\"a\"},\"b\"]".getBytes(StandardCharsets.UTF_8)).models())
            .extracting(OpenAiRelayModelsClient.RelayModel::id).containsExactly("a", "b");
        assertThat(OpenAiRelayModelsClient.parse(JSON, "{\"models\":[{\"name\":\"a\"}]}".getBytes(StandardCharsets.UTF_8)).models())
            .extracting(OpenAiRelayModelsClient.RelayModel::id).containsExactly("a");
        assertThat(OpenAiRelayModelsClient.parse(JSON, "{\"data\":[]}".getBytes(StandardCharsets.UTF_8)).models()).isEmpty();
    }

    @Test
    void stopsAtTwoThousandModelsAndSaysSo() {
        StringBuilder body = new StringBuilder("{\"data\":[");
        for (int i = 0; i < OpenAiRelayModelsClient.MAX_MODELS + 5; i++) {
            body.append(i == 0 ? "" : ",").append("{\"id\":\"model-").append(i).append("\"}");
        }
        body.append("]}");

        RelayModels models = OpenAiRelayModelsClient.parse(JSON, body.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(models.models()).hasSize(OpenAiRelayModelsClient.MAX_MODELS);
        assertThat(models.truncated()).isTrue();
        assertThat(OpenAiRelayModelsClient.parse(JSON, "{\"data\":[{\"id\":\"a\"}]}".getBytes(StandardCharsets.UTF_8)).truncated()).isFalse();
    }

    @Test
    void aBodyThatIsNotAListIsAFailureThatNamesTheBaseUrl() throws Exception {
        for (String body : List.of("<!doctype html><html>Welcome " + LEAK + "</html>", "", "{\"object\":\"list\"}", "{\"data\":5}", "\"text\"")) {
            Relay relay = relay((exchange, path) -> answer(exchange, 200, body));

            assertThatThrownBy(() -> client().list(account(relay.url())).block(WAIT))
                .isInstanceOf(UpstreamException.class)
                .satisfies(error -> assertThat(error.getMessage()).contains("base URL").doesNotContain("relay.internal", "sk-secret", "Welcome"));
        }
    }

    @Test
    void anErrorStatusIsTheStatusAndTheTypeOfTheMapperAndNeverTheWordsOfTheRelay() throws Exception {
        Relay relay = relay((exchange, path) -> answer(exchange, 401, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\","
            + "\"message\":\"" + LEAK + "\"}}"));

        assertThatThrownBy(() -> client().list(account(relay.url())).block(WAIT))
            .isInstanceOf(UpstreamException.class)
            .satisfies(error -> {
                assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(401);
                assertThat(error.getMessage()).isEqualTo("Source error 401 (invalid_api_key)");
            });
    }

    /** A key in a format the shapes of credentials do not know (32 hex digits) is masked only if the mapper is given it. */
    @Test
    void theKeyOfTheAccountIsNotLoggedWhenTheRelayRepeatsItInAnErrorOfTheModelList() throws Exception {
        String key = "f3a91c0d7b2e4c5a9e8d6b1a2c3d4e5f";
        ch.qos.logback.classic.Logger mapperLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RelayErrorMapper.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged = new ch.qos.logback.core.read.ListAppender<>();
        logged.start();
        mapperLog.addAppender(logged);
        try {
            Relay relay = relay((exchange, path) -> answer(exchange, 401, "{\"error\":{\"message\":\"Incorrect API key provided: " + key
                + "\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"));
            ProxyAccountEntity account = account(relay.url());
            account.setAccessToken(key);

            assertThatThrownBy(() -> client().list(account).block(WAIT)).isInstanceOf(UpstreamException.class);

            assertThat(logged.list).hasSize(1);
            assertThat(logged.list.get(0).getFormattedMessage()).doesNotContain(key).contains("[redacted]");
        } finally {
            mapperLog.detachAppender(logged);
            logged.stop();
        }
    }

    @Test
    void anAnswerLargerThanFourMegabytesIsRefused() throws Exception {
        Relay relay = relay((exchange, path) -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] piece = ("{\"id\":\"" + "x".repeat(60000) + "\"},").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseBody().write("{\"data\":[".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(piece);
                exchange.getResponseBody().flush();
            }
        });

        assertThatThrownBy(() -> client().list(account(relay.url())).block(WAIT))
            .isInstanceOf(UpstreamException.class)
            .satisfies(error -> assertThat(error.getMessage()).contains("size limit"));
    }

    @Test
    void refusesAnAccountThatIsNotThere() {
        assertThatThrownBy(() -> client().list(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- harness ---

    private static ProxyAccountEntity account(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("relay-account-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("sk-test");
        return account;
    }

    private static OpenAiRelayModelsClient client() {
        return new OpenAiRelayModelsClient(JSON, ProductionWebClients.builder().build());
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    @FunctionalInterface
    private interface Answer {
        void to(HttpExchange exchange, String path) throws Exception;
    }

    private Relay relay(Answer answer) throws IOException {
        Relay relay = new Relay(answer);
        open.add(relay);
        return relay;
    }

    private static final class Relay implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();

        Relay(Answer answer) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                    Map<String, String> seen = new HashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
                    headers.add(seen);
                    answer.to(exchange, exchange.getRequestURI().getPath());
                } catch (Exception clientLeft) {
                    // The client ended the exchange.
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> paths() {
            return paths;
        }

        List<Map<String, String>> headers() {
            return headers;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
