package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.EgressFailure;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.RelayNotSentException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 7.7, the HTTP half of a cross-protocol source, against a relay on the loopback address: the bytes of the one
 * request, the name of the model as it is written, the answer read in whatever pieces and shape it comes, a 404 that
 * is asked once, the time limit over the whole exchange, and what each kind of failure is for the caller that must not
 * generate twice. The accumulator, the decoder and the error mapper have their own tests; here they are met through
 * the wire.
 */
class OpenAiChatRelayClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LIMIT = Duration.ofSeconds(30);
    private static final Duration WAIT = Duration.ofSeconds(40);

    /** Words a relay could write that must never come back out: a host, a URL, a key and a piece of the prompt. */
    private static final String LEAK = "relay.internal.example.com https://relay.internal.example.com/v1 sk-secret-0123 the user's prompt about taxes";

    private static final String ANSWER = """
        data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":11}}

        data: {"choices":[{"index":0,"delta":{"content":"Héllo 你好 😀"}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"completion_tokens":5}}

        data: [DONE]

        """;

    private final List<AutoCloseable> open = new ArrayList<>();

    /** Netty and the codecs start on the first request; a time limit of a few hundred milliseconds must not count that. */
    @BeforeAll
    static void startTheHttpStack() throws Exception {
        Relay relay = new Relay((exchange, body) -> json(exchange, 200, completion("warm")));
        try {
            client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT);
        } finally {
            relay.close();
        }
    }

    @AfterEach
    void closeTheRelays() throws Exception {
        for (AutoCloseable relay : open) {
            relay.close();
        }
    }

    // --- the request ---

    @Test
    void sendsOneRequestWithTheBodyAsItWasGivenAndNothingFromTheClient() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("ok")));
        ObjectNode request = request("Claude-Sonnet-4.5", false);
        request.put("temperature", 0.25);
        request.putObject("metadata").put("note", "kept as written");

        Completion answer = client().complete(account(relay.url()), request, LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("ok");
        assertThat(relay.requests()).hasSize(1);
        Seen seen = relay.requests().get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/v1/chat/completions");
        assertThat(seen.body()).isEqualTo(JSON.writeValueAsBytes(request));
        assertThat(seen.header("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(seen.header("Content-Type")).isEqualTo("application/json");
        assertThat(seen.header("Accept")).isEqualTo("application/json");
        assertThat(seen.headerNames()).isSubsetOf(
            "accept", "accept-encoding", "authorization", "connection", "content-length", "content-type", "host", "user-agent");
    }

    @Test
    void askingForAStreamAcceptsAnEventStream() throws Exception {
        Relay relay = relay((exchange, body) -> eventStream(exchange, ANSWER));

        client().complete(account(relay.url()), request("claude-sonnet-4.5", true), LIMIT).block(WAIT);

        assertThat(relay.requests().get(0).header("Accept")).isEqualTo("text/event-stream, application/json");
    }

    @Test
    void theModelNameIsSentAsWrittenNotLoweredPrefixedOrRetried() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("ok")));
        OpenAiChatRelayClient client = client();
        // OpenAiUpstreamClient would send openai/claude-sonnet-4.5 for the first of these and lower-case all of them.
        for (String model : List.of("Claude-Sonnet-4.5", "Org/Model_X-1", "openai/GPT-5", "  spaced  ", "claude-sonnet-4.5")) {
            client.complete(account(relay.url()), request(model, false), LIMIT).block(WAIT);
        }

        assertThat(relay.requests()).extracting(seen -> JSON.readTree(seen.body()).path("model").asString(""))
            .containsExactly("Claude-Sonnet-4.5", "Org/Model_X-1", "openai/GPT-5", "  spaced  ", "claude-sonnet-4.5");
    }

    @Test
    void theStreamFlagAndOptionsAreTheCallersAndAreNeverForced() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("ok")));
        ObjectNode streaming = request("m", true);
        streaming.putObject("stream_options").put("include_usage", true);
        ObjectNode plain = request("m", false);

        client().complete(account(relay.url()), streaming, LIMIT).block(WAIT);
        client().complete(account(relay.url()), plain, LIMIT).block(WAIT);

        assertThat(JSON.readTree(relay.requests().get(0).body())).isEqualTo(streaming);
        assertThat(JSON.readTree(relay.requests().get(1).body())).isEqualTo(plain);
        assertThat(JSON.readTree(relay.requests().get(1).body()).has("stream_options")).isFalse();
    }

    @Test
    void theEndpointFollowsTheBaseUrlOfTheAccount() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("ok")));
        OpenAiChatRelayClient client = client();

        for (String base : List.of("", "/", "/v1", "/v1/", "/gateway/v2", "/v1/chat/completions")) {
            client.complete(account(relay.url() + base), request("m", false), LIMIT).block(WAIT);
        }

        assertThat(relay.requests()).extracting(Seen::path).containsExactly(
            "/v1/chat/completions", "/v1/chat/completions", "/v1/chat/completions", "/v1/chat/completions",
            "/gateway/v2/chat/completions", "/v1/chat/completions");
    }

    @Test
    void everySubscriptionIsOneRequestAndNothingIsSentBeforeIt() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("ok")));
        Mono<Completion> call = client().complete(account(relay.url()), request("m", false), LIMIT);

        assertThat(relay.requests()).isEmpty();
        call.block(WAIT);
        call.block(WAIT);

        assertThat(relay.requests()).hasSize(2);
    }

    @Test
    void refusesArgumentsThatCouldNotMakeARequest() {
        OpenAiChatRelayClient client = client();
        ProxyAccountEntity account = account("http://127.0.0.1:1");

        assertThatThrownBy(() -> client.complete(null, request("m", false), LIMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.complete(account, null, LIMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.complete(account, JSON.createArrayNode(), LIMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.complete(account, request("m", false), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.complete(account, request("m", false), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.complete(account, request("m", false), Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    // --- the answer ---

    @Test
    void readsAJsonAnswerWithToolCallsAndUsage() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, """
            {"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":"Looking.",
              "tool_calls":[{"id":"call_a","type":"function","function":{"name":"Bash","arguments":"{\\"command\\":\\"pwd\\"}"}}]},
              "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":30,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":10}}}
            """));

        Completion answer = client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("Looking.");
        assertThat(answer.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(answer.toolUses()).hasSize(1);
        PlatformToolUse use = answer.toolUses().get(0);
        assertThat(use.id()).isEqualTo("call_a");
        assertThat(use.name()).isEqualTo("Bash");
        assertThat(use.input().path("command").asString("")).isEqualTo("pwd");
        assertThat(answer.usage().path("prompt_tokens").asLong()).isEqualTo(30);
        assertThat(answer.usage().path("prompt_tokens_details").path("cached_tokens").asLong()).isEqualTo(10);
    }

    @Test
    void readsAnEventStreamAndMergesItsUsage() throws Exception {
        Relay relay = relay((exchange, body) -> eventStream(exchange, ANSWER));

        Completion answer = client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("Héllo 你好 😀");
        assertThat(answer.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(answer.usage().path("prompt_tokens").asLong()).isEqualTo(11);
        assertThat(answer.usage().path("completion_tokens").asLong()).isEqualTo(5);
    }

    @Test
    void readsAnEventStreamWhenAJsonBodyWasAskedFor() throws Exception {
        Relay relay = relay((exchange, body) -> eventStream(exchange, ANSWER));

        Completion answer = client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("Héllo 你好 😀");
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void readsAJsonBodyWhenAStreamWasAskedFor() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("whole")));

        Completion answer = client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("whole");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 7, 64})
    void readsAStreamCutInPiecesAnywhereAnd(int pieceSize) throws Exception {
        // One byte at a time splits every multi-byte character and every line end; CRLF puts a line end between pieces.
        byte[] body = ANSWER.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Relay relay = relay((exchange, request) -> eventStream(exchange, body, pieceSize));

        Completion answer = client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("Héllo 你好 😀");
        assertThat(answer.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(answer.usage().path("prompt_tokens").asLong()).isEqualTo(11);
    }

    @Test
    void anAnswerKnownToBeAFailureEndsTheExchangeWhileTheRelayIsStillSending() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(("data: {\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"insufficient_quota\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            released.await(30, TimeUnit.SECONDS);
        });

        try {
            // The body never ends; only reading as it comes can fail this call.
            assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", true), LIMIT).block(Duration.ofSeconds(10)))
                .isInstanceOf(RelayErrorException.class)
                .hasMessage("Source error 402 (insufficient_quota)")
                .satisfies(error -> assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(402));
        } finally {
            released.countDown();
        }
    }

    @Test
    void aSuccessfulResponseThatHoldsAnErrorIsTheStatusTheErrorDeserves() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200,
            "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .hasMessage("Source error 402 (insufficient_quota)");
    }

    @Test
    void aPageOfHtmlIsARecoverableBadGatewayThatAnotherSourceMayAnswer() throws Exception {
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            byte[] page = "<html><body>Bad gateway from relay.internal.example.com</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
        });

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(UpstreamException.class)
            .isNotInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                UpstreamException failure = (UpstreamException) error;
                assertThat(failure.getStatusCode()).isEqualTo(502);
                assertThat(failure.isRecoverable()).isTrue();
                assertThat(failure.isGenerationStarted()).isFalse();
                assertThat(failure.getMessage()).doesNotContain("relay.internal");
            });
    }

    @Test
    void aPageOfHtmlWithAStyleSheetIsStillNoStream() throws Exception {
        // A line of the style sheet that starts with a colon is a comment line in an event stream, and a comment is a
        // sign of life: the page of a relay's console, answered to a wrong base URL, must not pass for one.
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            byte[] page = ("<!DOCTYPE html>\n<html><head><style>\n:root{--bg:#fff}\n</style></head>"
                + "<body>relay.internal.example.com console</body></html>\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
        });

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(UpstreamException.class)
            .isNotInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                UpstreamException failure = (UpstreamException) error;
                assertThat(failure.getStatusCode()).isEqualTo(502);
                assertThat(failure.isRecoverable()).isTrue();
                assertThat(failure.isGenerationStarted()).isFalse();
                assertThat(failure.getMessage()).doesNotContain("relay.internal");
            });
    }

    @Test
    void aServerErrorThatComesAfterThirtySecondsIsAnInterruptionBecauseTheRelayMayHaveBeenGenerating() throws Exception {
        AtomicLong clock = new AtomicLong();
        OpenAiChatRelayClient slow = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build(), clock::get);
        Relay relay = relay((exchange, body) -> {
            clock.addAndGet(Duration.ofSeconds(31).toNanos());
            json(exchange, 500, "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"server_error\"}}");
        });

        assertThatThrownBy(() -> slow.complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                assertThat(error.getMessage()).doesNotContain("relay.internal", "sk-secret", "taxes");
                assertThat(error.getCause()).isNull();
                assertThat(((UpstreamException) error).isGenerationStarted()).isTrue();
            });
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void theSameServerErrorInAMomentIsAnErrorTheRelayRefusedWith() throws Exception {
        AtomicLong clock = new AtomicLong();
        OpenAiChatRelayClient quick = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build(), clock::get);
        Relay relay = relay((exchange, body) -> {
            clock.addAndGet(Duration.ofSeconds(29).toNanos());
            json(exchange, 500, "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"server_error\"}}");
        });

        assertThatThrownBy(() -> quick.complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .satisfies(error -> assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(503));
    }

    /**
     * A relay that is streaming has answered 200 before its own upstream has: the same late failure can only reach us
     * inside the response. It is judged by the same clock as an error status, so a streaming member is not the one
     * place where a long wait for the upstream is normal and the answer is asked for a second time.
     */
    @Test
    void aServerErrorInsideAnEventStreamThatComesAfterThirtySecondsIsAnInterruptionToo() throws Exception {
        AtomicLong clock = new AtomicLong();
        OpenAiChatRelayClient slow = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build(), clock::get);
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            clock.addAndGet(Duration.ofSeconds(31).toNanos());
            out.write(("data: {\"error\":{\"type\":\"server_error\",\"message\":\"" + LEAK + "\"}}\n\n").getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> slow.complete(account(relay.url()), request("m", true), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                assertThat(error.getMessage()).doesNotContain("relay.internal", "sk-secret", "taxes");
                assertThat(((UpstreamException) error).isGenerationStarted()).isTrue();
            });
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void aServerErrorInsideAJsonAnswerThatComesAfterThirtySecondsIsAnInterruptionAndInAMomentItIsNot() throws Exception {
        AtomicLong clock = new AtomicLong();
        OpenAiChatRelayClient client = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build(), clock::get);
        AtomicInteger asked = new AtomicInteger();
        Relay relay = relay((exchange, body) -> {
            clock.addAndGet(Duration.ofSeconds(asked.getAndIncrement() == 0 ? 29 : 31).toNanos());
            json(exchange, 200, "{\"error\":{\"code\":500,\"message\":\"" + LEAK + "\"}}");
        });

        assertThatThrownBy(() -> client.complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .satisfies(error -> {
                assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(500);
                assertThat(((UpstreamException) error).isGenerationStarted()).isFalse();
            });
        assertThatThrownBy(() -> client.complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain("relay.internal", "sk-secret", "taxes"));
    }

    @Test
    void aRefusalInsideAJsonAnswerIsARefusalHoweverLateItComes() throws Exception {
        AtomicLong clock = new AtomicLong();
        OpenAiChatRelayClient slow = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build(), clock::get);
        Relay relay = relay((exchange, body) -> {
            clock.addAndGet(Duration.ofSeconds(40).toNanos());
            json(exchange, 200, "{\"error\":{\"message\":\"No available channel for model m\",\"type\":\"new_api_error\"}}");
        });

        assertThatThrownBy(() -> slow.complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .satisfies(error -> assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(404));
    }

    @Test
    void aLateAnswerThatSaysItDidNotGenerateAnythingIsStillThatAnswer() throws Exception {
        // The relay waited for a channel for 40 seconds and said there was none; it was told the prompt was too long;
        // it was out of money; the key is wrong. None of these produced anything, however long they took.
        AtomicLong clock = new AtomicLong();
        OpenAiChatRelayClient slow = new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build(), clock::get);
        List<Integer> statuses = List.of(503, 500, 429, 401, 402);
        List<String> bodies = List.of(
            "{\"error\":{\"message\":\"No available channel for model m\",\"type\":\"new_api_error\"}}",
            "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\",\"type\":\"server_error\"}}",
            "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}",
            "{\"error\":{\"message\":\"bad key\",\"type\":\"authentication_error\"}}",
            "{\"error\":{\"message\":\"out of money\",\"type\":\"insufficient_quota\"}}");
        List<Integer> mapped = List.of(404, 400, 429, 401, 402);
        AtomicInteger asked = new AtomicInteger();
        Relay relay = relay((exchange, body) -> {
            int n = asked.getAndIncrement();
            clock.addAndGet(Duration.ofSeconds(40).toNanos());
            json(exchange, statuses.get(n), bodies.get(n));
        });

        for (int n = 0; n < statuses.size(); n++) {
            int expected = mapped.get(n);
            assertThatThrownBy(() -> slow.complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
                .as("status " + statuses.get(n))
                .isInstanceOf(RelayErrorException.class)
                .satisfies(error -> assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(expected));
        }
    }

    @Test
    void aTimeoutOfAGatewayInFrontOfTheRelayKeepsItsStatusForTheDispatcherToJudge() throws Exception {
        List<Integer> statuses = List.of(504, 524, 408);
        AtomicInteger asked = new AtomicInteger();
        Relay relay = relay((exchange, body) -> json(exchange, statuses.get(asked.getAndIncrement()),
            "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"server_error\"}}"));

        for (int status : statuses) {
            assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
                .isInstanceOf(RelayErrorException.class)
                .satisfies(error -> assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(status));
        }
    }

    @Test
    void theKeyThatWasSentIsNotLoggedWhenTheRelayRepeatsIt() throws Exception {
        ch.qos.logback.classic.Logger mapperLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RelayErrorMapper.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged = new ch.qos.logback.core.read.ListAppender<>();
        logged.start();
        mapperLog.addAppender(logged);
        try {
            AtomicInteger asked = new AtomicInteger();
            Relay relay = relay((exchange, body) -> {
                if (asked.getAndIncrement() == 0) {
                    json(exchange, 401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-relay-0123456789. You can find your key at example.com\","
                        + "\"type\":\"authentication_error\"}}");
                } else {
                    json(exchange, 200, "{\"error\":{\"message\":\"key sk-relay-0123456789 is out of quota\",\"type\":\"insufficient_quota\"}}");
                }
            });
            ProxyAccountEntity account = account(relay.url());
            account.setAccessToken("account-token-0123456789");
            Relay echoing = relay((exchange, body) -> json(exchange, 401,
                "{\"error\":{\"message\":\"Incorrect API key provided: account-token-0123456789\",\"type\":\"authentication_error\"}}"));
            ProxyAccountEntity echoed = account(echoing.url());
            echoed.setAccessToken("account-token-0123456789");

            assertThatThrownBy(() -> client().complete(account, request("m", false), LIMIT).block(WAIT)).isInstanceOf(RelayErrorException.class);
            assertThatThrownBy(() -> client().complete(account, request("m", false), LIMIT).block(WAIT)).isInstanceOf(RelayErrorException.class);
            assertThatThrownBy(() -> client().complete(echoed, request("m", false), LIMIT).block(WAIT)).isInstanceOf(RelayErrorException.class);
            assertThatThrownBy(() -> client().complete(echoed, request("m", true), LIMIT).block(WAIT)).isInstanceOf(RelayErrorException.class);

            assertThat(logged.list).hasSize(4);
            for (ch.qos.logback.classic.spi.ILoggingEvent event : logged.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("sk-relay-0123456789", "account-token-0123456789").contains("[redacted]");
            }
        } finally {
            mapperLog.detachAppender(logged);
            logged.stop();
        }
    }

    @Test
    void anAnswerOverTheSizeLimitIsAnInterruption() throws Exception {
        byte[] head = "{\"choices\":[{\"message\":{\"content\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] filler = new byte[1024 * 1024];
        java.util.Arrays.fill(filler, (byte) 'x');
        int megabytes = 18;
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, head.length + (long) megabytes * filler.length);
            OutputStream out = exchange.getResponseBody();
            out.write(head);
            for (int i = 0; i < megabytes; i++) {
                out.write(filler);
            }
        });

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .hasMessageContaining("size limit");
    }

    // --- the relay says no ---

    @Test
    void aNotFoundIsAskedOnceAndIsNotRetriedUnderAnotherName() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 404,
            "{\"error\":{\"message\":\"The model `Claude-Sonnet-4.5` does not exist " + LEAK + "\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("openai/Claude-Sonnet-4.5", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .satisfies(error -> {
                UpstreamException failure = (UpstreamException) error;
                assertThat(failure.getStatusCode()).isEqualTo(404);
                assertThat(failure.isGenerationStarted()).isFalse();
                assertThat(failure.getMessage()).isEqualTo("Source error 404 (model_not_found)");
            });
        // OpenAiUpstreamClient asks a second time without the openai/ prefix.
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void anErrorStatusIsMappedAndNothingTheRelayWroteIsLeftInTheException() throws Exception {
        List<Integer> statuses = List.of(400, 401, 429, 502, 503);
        List<String> bodies = List.of(
            "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"invalid_request_error\"}}",
            "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"authentication_error\"}}",
            "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"rate_limit_error\"}}",
            "<html><body>" + LEAK + "</body></html>",
            "{\"error\":{\"message\":\"" + LEAK + "\",\"type\":\"overloaded_error\"}}");
        AtomicInteger asked = new AtomicInteger();
        Relay relay = relay((exchange, body) -> {
            int n = asked.getAndIncrement();
            json(exchange, statuses.get(n), bodies.get(n));
        });

        for (int status : statuses) {
            assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
                .as("status " + status)
                .isInstanceOf(RelayErrorException.class)
                .satisfies(error -> {
                    UpstreamException failure = (UpstreamException) error;
                    assertThat(failure.getStatusCode()).isEqualTo(status);
                    assertThat(failure.getMessage()).doesNotContain("relay.internal", "sk-secret", "taxes", "http");
                });
        }
    }

    @Test
    void keepsTheRetryAfterOfTheRelay() throws Exception {
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Retry-After", "7");
            json(exchange, 429, "{\"error\":{\"type\":\"rate_limit_error\"}}");
        });

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .satisfies(error -> assertThat(UpstreamException.retryAfterSeconds(error)).isEqualTo(7));
    }

    @Test
    void aContextLimitErrorIsMarked() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 400,
            "{\"error\":{\"message\":\"This model's maximum context length is 200000 tokens\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}"));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayErrorException.class)
            .satisfies(error -> assertThat(((RelayErrorException) error).isContextExceeded()).isTrue());
    }

    @Test
    void anErrorBodyThatNeverEndsStillGivesTheStatus() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(503, 0);
            OutputStream out = exchange.getResponseBody();
            byte[] filler = new byte[64 * 1024];
            java.util.Arrays.fill(filler, (byte) 'x');
            // More than the client reads, and then silence: the status must not wait for an end that does not come.
            out.write(filler);
            out.write(filler);
            out.flush();
            released.await(30, TimeUnit.SECONDS);
        });

        try {
            assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(Duration.ofSeconds(8)))
                .isInstanceOf(RelayErrorException.class)
                .satisfies(error -> assertThat(((UpstreamException) error).getStatusCode()).isEqualTo(503));
        } finally {
            released.countDown();
        }
    }

    // --- [DONE] ends the answer ---

    private static String chunked(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return Integer.toHexString(bytes.length) + "\r\n" + text + "\r\n";
    }

    private static final String EVENT_STREAM_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n";

    /**
     * The answer is whole at [DONE], and so is what was paid for: a relay that closes the connection badly afterwards,
     * without the last chunk that ends the body, has not made an interruption of it.
     */
    @Test
    void anAnswerThatSaidDoneIsTheAnswerEvenIfTheRelayClosesTheConnectionBadlyAfterIt() throws Exception {
        RawRelay relay = rawRelay((socket, out) -> {
            out.write(EVENT_STREAM_HEAD.getBytes(StandardCharsets.US_ASCII));
            out.write(chunked(ANSWER).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(100);
            socket.close();
        });

        Completion answer = client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT);

        assertThat(answer.content()).isEqualTo("H\u00e9llo \u4f60\u597d \ud83d\ude00");
        assertThat(answer.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(answer.usage().path("completion_tokens").asLong()).isEqualTo(5);
        assertThat(relay.requestCount()).isEqualTo(1);
    }

    private static byte[] chunkedBytes(byte[] bytes) {
        byte[] size = (Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] framed = new byte[size.length + bytes.length + 2];
        System.arraycopy(size, 0, framed, 0, size.length);
        System.arraycopy(bytes, 0, framed, size.length, bytes.length);
        framed[framed.length - 2] = '\r';
        framed[framed.length - 1] = '\n';
        return framed;
    }

    private static byte[] withAfterDone(byte[] stray) {
        byte[] answer = ANSWER.getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(answer, answer.length + stray.length);
        System.arraycopy(stray, 0, body, answer.length, stray.length);
        return body;
    }

    /**
     * What the relay writes after [DONE] is outside the answer, and where TCP cuts the stream must not decide whether the
     * answer was whole: bytes that are not text, or the first byte of a character that never ends, in the very read that
     * carried [DONE] are the same as in a later one, which is never read. Otherwise a paid answer reaches the client as an
     * interruption, and the client that retries it generates it again.
     */
    @Test
    void whatTheRelayWritesAfterDoneInTheSameReadIsNotPartOfTheAnswer() throws Exception {
        byte[][] strays = {
            {(byte) 0xFF},
            {(byte) 0xC3, (byte) 0x28},
            {(byte) 0xE4},
            Arrays.copyOf(": \u7ed3\u675f\n\n".getBytes(StandardCharsets.UTF_8), 3)
        };
        for (byte[] stray : strays) {
            for (boolean sameRead : new boolean[] {true, false}) {
                RawRelay relay = rawRelay((socket, out) -> {
                    out.write(EVENT_STREAM_HEAD.getBytes(StandardCharsets.US_ASCII));
                    if (sameRead) {
                        out.write(chunkedBytes(withAfterDone(stray)));
                        out.flush();
                    } else {
                        out.write(chunkedBytes(ANSWER.getBytes(StandardCharsets.UTF_8)));
                        out.flush();
                        Thread.sleep(200);
                        out.write(chunkedBytes(stray));
                        out.flush();
                    }
                    Thread.sleep(300);
                    socket.close();
                });

                Completion answer = client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT);

                String where = Arrays.toString(stray) + (sameRead ? " in the read of [DONE]" : " in a later read");
                assertThat(answer.content()).as(where).isEqualTo("H\u00e9llo \u4f60\u597d \ud83d\ude00");
                assertThat(answer.usage().path("completion_tokens").asLong()).as(where).isEqualTo(5);
                assertThat(relay.requestCount()).as(where).isEqualTo(1);
            }
        }
    }

    /** A body of a completion that is cut short but ends cleanly (the last chunk is written) is not an answer to try elsewhere. */
    @Test
    void aJsonCompletionCutShortBeforeItsChoicesIsAnInterruptionAndIsNotSentAgain() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200,
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"claude\",\"cho"));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> assertThat(((UpstreamException) error).isGenerationStarted()).isTrue());
        assertThat(relay.requests()).hasSize(1);
    }

    /** A relay that keeps the response open after [DONE] must not hold the request until the time limit, nor fail it. */
    @Test
    void anAnswerThatSaidDoneIsTheAnswerAtOnceAndTheConnectionIsClosedWhileTheRelayKeepsItOpen() throws Exception {
        CountDownLatch clientClosed = new CountDownLatch(1);
        RawRelay relay = rawRelay((socket, out) -> {
            out.write(EVENT_STREAM_HEAD.getBytes(StandardCharsets.US_ASCII));
            out.write(chunked(ANSWER).getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Held open: it ends when the client closes its side.
            InputStream in = socket.getInputStream();
            while (in.read() >= 0) {
                // Nothing more is expected of the client.
            }
            clientClosed.countDown();
        });

        long started = System.nanoTime();
        Completion answer = client().complete(account(relay.url()), request("m", true), Duration.ofSeconds(20)).block(WAIT);

        assertThat(answer.content()).isEqualTo("H\u00e9llo \u4f60\u597d \ud83d\ude00");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(clientClosed.await(10, TimeUnit.SECONDS)).as("the client closed the connection").isTrue();
    }

    /** The other half of the same promise: a stream that never says it is whole is still cut short, because nothing says it is done. */
    @Test
    void aStreamThatNeverSaidDoneIsNotCompleteBecauseTheRelayClosedTheConnection() throws Exception {
        RawRelay relay = rawRelay((socket, out) -> {
            out.write(EVENT_STREAM_HEAD.getBytes(StandardCharsets.US_ASCII));
            out.write(chunked("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(100);
            socket.close();
        });

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class);
    }

    // --- the time limit ---

    @Test
    void theTimeLimitCoversAStreamThatKeepsComingAndClosesTheConnection() throws Exception {
        CountDownLatch relayNoticed = new CountDownLatch(1);
        Relay relay = relay((exchange, body) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                // A relay that is generating: data arrives all the time, so no read timeout would ever fire.
                for (int i = 0; i < 400; i++) {
                    out.write(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"word" + i + " \"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(25);
                }
            } catch (IOException clientLeft) {
                relayNoticed.countDown();
            }
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", true), Duration.ofSeconds(1)).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                UpstreamException failure = (UpstreamException) error;
                assertThat(failure.getStatusCode()).isEqualTo(502);
                assertThat(failure.isRecoverable()).isTrue();
                assertThat(failure.isGenerationStarted()).isTrue();
                assertThat(failure.getMessage()).contains("time limit");
            });

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(relayNoticed.await(10, TimeUnit.SECONDS)).as("the connection was closed").isTrue();
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void theTimeLimitCoversARelayThatNeverAnswers() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        Relay relay = relay((exchange, body) -> released.await(30, TimeUnit.SECONDS));

        try {
            assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), Duration.ofSeconds(1)).block(WAIT))
                .isInstanceOf(RelayAbortedAfterDispatchException.class)
                .hasMessageContaining("time limit");
        } finally {
            released.countDown();
        }
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void aLimitThatRunsOutBeforeTheRequestWasHandedOverIsNotAnInterruption() {
        // The connection never gets ready: nothing was written, so another source may answer.
        WebClient stuck = ProductionWebClients.builder().filter((request, next) -> Mono.never()).build();

        assertThatThrownBy(() -> new OpenAiChatRelayClient(JSON, stuck)
            .complete(account("http://127.0.0.1:1"), request("m", false), Duration.ofMillis(300)).block(WAIT))
            .isInstanceOf(UpstreamException.class)
            .isNotInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                UpstreamException failure = (UpstreamException) error;
                assertThat(failure.getStatusCode()).isEqualTo(502);
                assertThat(failure.isRecoverable()).isTrue();
                assertThat(failure.isGenerationStarted()).isFalse();
            });
    }

    @Test
    void anAnswerInsideTheLimitIsNotCutOff() throws Exception {
        Relay relay = relay((exchange, body) -> {
            Thread.sleep(200);
            eventStream(exchange, ANSWER);
        });

        Completion answer = client().complete(account(relay.url()), request("m", true), Duration.ofSeconds(5)).block(WAIT);

        assertThat(answer.content()).isEqualTo("Héllo 你好 😀");
    }

    // --- the exchange breaks ---

    @Test
    void aConnectionThatClosesAfterTheRequestIsAnInterruptionAndTheRequestIsNotSentAgain() throws Exception {
        RawRelay relay = rawRelay((socket, out) -> socket.close());

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .hasMessageContaining("after the request was sent")
            .satisfies(error -> assertThat(error.getCause()).isNotNull());

        assertThat(relay.requestCount()).isEqualTo(1);
    }

    /**
     * Whether the events and the damaged bytes that follow them come in one read or in two is the network's doing. The
     * relay has already shown life in both, and an answer that is made again elsewhere because of where TCP cut it is
     * generated, and paid for, twice.
     */
    @Test
    void damagedBytesInTheSameReadAsTheEventsBeforeThemAreAnInterruptionAndNotAnAnswerToTryElsewhere() throws Exception {
        byte[] events = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Here is the plan: \"}}]}\n\n").getBytes(StandardCharsets.UTF_8);
        byte[] damaged = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'};
        byte[] body = new byte[events.length + damaged.length];
        System.arraycopy(events, 0, body, 0, events.length);
        System.arraycopy(damaged, 0, body, events.length, damaged.length);
        Relay relay = relay((exchange, request) -> eventStream(exchange, body, Integer.MAX_VALUE));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> assertThat(((UpstreamException) error).isGenerationStarted()).isTrue())
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain("plan"));
        assertThat(relay.requests()).hasSize(1);
    }

    /** The model spent a small budget on reasoning the relay does not show, and the relay left the usage out too. */
    @Test
    void anEmptyAnswerThatRanOutOfTokensIsAnInterruptionAndNotAnEmptyAnswerToTryElsewhere() throws Exception {
        Relay relay = relay((exchange, request) -> json(exchange, 200,
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"length\"}]}"));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> assertThat(((UpstreamException) error).isGenerationStarted()).isTrue());
        assertThat(relay.requests()).hasSize(1);
    }

    @Test
    void aStreamThatIsCutOffAfterOutputIsAnInterruption() throws Exception {
        RawRelay relay = rawRelay((socket, out) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String event = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n";
            out.write((Integer.toHexString(event.length()) + "\r\n" + event + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.close();
        });

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> {
                UpstreamException failure = (UpstreamException) error;
                assertThat(failure.isGenerationStarted()).isTrue();
                assertThat(failure.getMessage()).doesNotContain("partial");
            });
        assertThat(relay.requestCount()).isEqualTo(1);
    }

    @Test
    void aStreamThatEndsCleanlyWithoutFinishingIsWhatTheAccumulatorSaysItIs() throws Exception {
        // The chunked body is complete and the event stream is not: no finish reason, no [DONE].
        Relay relay = relay((exchange, body) -> eventStream(exchange, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"));

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", true), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .hasMessageContaining("ended before the answer was complete");
    }

    @Test
    void aReadTimeoutOfTheTransportAfterTheRequestIsAnInterruption() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        Relay relay = relay((exchange, body) -> released.await(30, TimeUnit.SECONDS));
        WebClient impatient = ProductionWebClients.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofMillis(300))))
            .build();

        try {
            assertThatThrownBy(() -> new OpenAiChatRelayClient(JSON, impatient)
                .complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
                .isInstanceOf(RelayAbortedAfterDispatchException.class)
                .hasMessageContaining("after the request was sent")
                .satisfies(error -> assertThat(error.getCause()).isNotNull());
        } finally {
            released.countDown();
        }
    }

    @Test
    void aRequestThatNeverLeftKeepsItsOwnError() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        String base = "http://127.0.0.1:" + closedPort;

        assertThatThrownBy(() -> client().complete(account(base), request("m", false), LIMIT).block(WAIT))
            .isNotInstanceOf(UpstreamException.class)
            .isInstanceOf(RelayNotSentException.class)
            .satisfies(error -> assertThat(rootCause(error)).isInstanceOf(ConnectException.class))
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain("127.0.0.1"));
    }

    /**
     * The client saw that the request was never handed to the connection, which is more than a list of error classes
     * knows: a relay port or a firewall that resets or closes the handshake gives a plain socket error, and a TLS
     * handshake spoken to a port that answers in plain text another, neither of which says by its class that nothing was
     * sent. The caller must be told it, or it will take them for a request that may have been generated.
     */
    @Test
    void aHandshakeThatTheRelaysPortResetsOrClosesIsARequestThatNeverLeft() throws Exception {
        for (boolean reset : new boolean[] {true, false}) {
            HandshakeBreaker breaker = new HandshakeBreaker(reset);
            open.add(breaker);

            assertThatThrownBy(() -> client().complete(account("https://127.0.0.1:" + breaker.port()), request("m", false), LIMIT).block(WAIT))
                .as(reset ? "reset" : "closed")
                .isInstanceOf(RelayNotSentException.class)
                .isNotInstanceOf(UpstreamException.class)
                .satisfies(error -> assertThat(error.getCause()).isNotNull())
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("127.0.0.1", String.valueOf(breaker.port())));
            // The transport may try the connection once more on its own; no request was ever written to either.
            assertThat(breaker.connections()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void aTlsHandshakeSpokenToAPortThatAnswersInPlainTextIsARequestThatNeverLeft() throws Exception {
        Relay relay = relay((exchange, body) -> json(exchange, 200, completion("never read")));

        assertThatThrownBy(() -> client().complete(account(relay.url().replace("http://", "https://")), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayNotSentException.class)
            .satisfies(error -> assertThat(error.getCause()).isNotNull());

        // The relay saw bytes of a handshake, not a request it could answer.
        assertThat(relay.requests()).isEmpty();
    }

    @Test
    void aFailureAfterTheRequestWasHandedOverIsNeverMarkedAsNotSent() throws Exception {
        RawRelay relay = rawRelay((socket, out) -> socket.close());

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .isNotInstanceOf(RelayNotSentException.class);
    }

    @Test
    void anEgressFailureSaysForItselfWhetherTheRequestMayHaveBeenSent() throws Exception {
        EgressException notSent = EgressException.notSent(EgressFailure.PROXY_CONNECT_FAILED, new IOException("no route"));
        EgressException maybeSent = EgressException.maybeSent(EgressFailure.PROXY_TRANSPORT_FAILED, new IOException("reset"));

        assertThatThrownBy(() -> failingClient(notSent).complete(account("http://127.0.0.1:1"), request("m", false), LIMIT).block(WAIT))
            .isSameAs(notSent);
        assertThatThrownBy(() -> failingClient(maybeSent).complete(account("http://127.0.0.1:1"), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .hasCause(maybeSent);
    }

    @Test
    void anInterruptionNamesNeitherTheTransportErrorNorTheRelay() throws Exception {
        RawRelay relay = rawRelay((socket, out) -> socket.close());

        assertThatThrownBy(() -> client().complete(account(relay.url()), request("m", false), LIMIT).block(WAIT))
            .isInstanceOf(RelayAbortedAfterDispatchException.class)
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain("127.0.0.1", String.valueOf(relay.port()), "sk-test"));
    }

    // --- harness ---

    private static ObjectNode request(String model, boolean stream) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        request.put("stream", stream);
        request.putArray("messages").addObject().put("role", "user").put("content", "Hi");
        return request;
    }

    private static String completion(String text) {
        return "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text
            + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";
    }

    private static ProxyAccountEntity account(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("relay-account-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("sk-test");
        return account;
    }

    private static OpenAiChatRelayClient client() {
        return new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build());
    }

    /** A client whose connection step fails with the given error, as the egress transport does before or after it sent. */
    private static OpenAiChatRelayClient failingClient(RuntimeException error) {
        WebClient failing = ProductionWebClients.builder()
            .filter((request, next) -> Mono.error(error))
            .build();
        return new OpenAiChatRelayClient(JSON, failing);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void eventStream(HttpExchange exchange, String body) throws IOException {
        eventStream(exchange, body.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
    }

    /** The body in chunked pieces of {@code pieceSize} bytes, each flushed, so each reaches the client as a piece of its own. */
    private static void eventStream(HttpExchange exchange, byte[] body, int pieceSize) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        for (int from = 0; from < body.length; from += pieceSize) {
            out.write(body, from, Math.min(pieceSize, body.length - from));
            out.flush();
        }
    }

    private record Seen(String method, String path, Map<String, String> headers, byte[] body) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        Set<String> headerNames() {
            return headers.keySet();
        }
    }

    @FunctionalInterface
    private interface Answer {
        void to(HttpExchange exchange, byte[] body) throws Exception;
    }

    private Relay relay(Answer answer) throws IOException {
        Relay relay = new Relay(answer);
        open.add(relay);
        return relay;
    }

    /** The JDK server on the loopback address, answering as the test says, and writing down what it was asked. */
    private static final class Relay implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Seen> requests = new CopyOnWriteArrayList<>();

        Relay(Answer answer) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Map<String, String> headers = new HashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
                    requests.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
                    answer.to(exchange, body);
                } catch (Exception clientLeft) {
                    // The test ended the exchange, or the client did.
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<Seen> requests() {
            return requests;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class HandshakeBreaker implements AutoCloseable {

        private final ServerSocket server;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /** A port that reads the first bytes of whatever connects and then resets (or just closes) the connection. */
        HandshakeBreaker(boolean reset) throws IOException {
            server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        connections.incrementAndGet();
                        try {
                            socket.getInputStream().read(new byte[512]);
                            if (reset) {
                                socket.setSoLinger(true, 0);
                            }
                            socket.close();
                        } catch (IOException ended) {
                            // The client left first.
                        }
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            }, "handshake-breaker");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    @FunctionalInterface
    private interface Script {
        void run(Socket socket, OutputStream out) throws Exception;
    }

    private RawRelay rawRelay(Script script) throws IOException {
        RawRelay relay = new RawRelay(script);
        open.add(relay);
        return relay;
    }

    /** A server that reads a whole request and then does what the script says with the socket, for what HttpServer cannot do. */
    private static final class RawRelay implements AutoCloseable {

        private final ServerSocket server;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        RawRelay(Script script) throws IOException {
            server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        Thread worker = new Thread(() -> serve(socket, script), "raw-relay-worker");
                        worker.setDaemon(true);
                        worker.start();
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            }, "raw-relay-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void serve(Socket socket, Script script) {
            try {
                socket.setTcpNoDelay(true);
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int matched = 0;
                while (matched < 4) {
                    int next = in.read();
                    if (next < 0) {
                        return;
                    }
                    head.write(next);
                    matched = next == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : next == '\r' ? 1 : 0;
                }
                String text = head.toString(StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                int at = text.indexOf("content-length:");
                int length = at < 0 ? 0 : Integer.parseInt(text.substring(at + "content-length:".length(), text.indexOf("\r\n", at)).strip());
                in.readNBytes(length);
                requests.incrementAndGet();
                script.run(socket, socket.getOutputStream());
            } catch (Exception ended) {
                // The client left, or the script closed the socket.
            }
        }

        int port() {
            return server.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        int requestCount() {
            return requests.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
