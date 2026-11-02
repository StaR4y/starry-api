package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 7.11: what a relay's error becomes. Every kind of error the table names, with the shapes relays really use
 * (OpenAI's own, Anthropic's, new-api's, OpenRouter's), the order in which the relay's words decide, and the one
 * promise of the class: nothing the relay wrote reaches the exception, only the server log.
 */
class RelayErrorMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What Anthropic answers when the prompt fits the window but not beside max_tokens (the Claude models before the 4.5 generation:
     * 3.x, Sonnet 4, Opus 4, Opus 4.1; Bedrock, Vertex, and relays in front of them).
     */
    private static final String ANTHROPIC_BESIDE_OUTPUT =
        "input length and `max_tokens` exceed context limit: 188240 + 21333 > 200000, decrease input length or `max_tokens` and try again";

    /** Words a relay could write that must never come back out: a host, a URL, a key and a piece of the prompt. */
    private static final String LEAK = "relay.internal.example.com https://relay.internal.example.com/v1 sk-secret-0123 the user's prompt about taxes";

    private final RelayErrorMapper mapper = new RelayErrorMapper(JSON);
    private final Logger mapperLog = (Logger) LoggerFactory.getLogger(RelayErrorMapper.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level levelBefore;

    @BeforeEach
    void captureTheLog() {
        levelBefore = mapperLog.getLevel();
        mapperLog.setLevel(Level.DEBUG);
        logged.start();
        mapperLog.addAppender(logged);
    }

    @AfterEach
    void releaseTheLog() {
        mapperLog.detachAppender(logged);
        logged.stop();
        mapperLog.setLevel(levelBefore);
    }

    private static String error(String type, String code, String message) {
        return "{\"error\":{\"message\":" + quote(message) + ",\"type\":" + quote(type) + ",\"param\":null,\"code\":" + quote(code) + "}}";
    }

    private static String quote(String value) {
        return value == null ? "null" : JSON.writeValueAsString(value);
    }

    /** A string shaped like a Google API key, joined at run time so that no literal in the source looks like one. */
    private static String googleKeyShape() {
        return "AIza" + "SyA-1234567890abcdefghijklmnopqrstu";
    }

    private RelayErrorException map(int httpStatus, String body) {
        return mapper.fromResponse(httpStatus, body, null, "account 7", null);
    }

    // ---------------------------------------------------------------- the table of design 7.11

    static Stream<Arguments> theKindsOfError() {
        return Stream.of(
            // rate limit -> 429
            Arguments.of(429, error("requests", "rate_limit_exceeded", "Rate limit reached for gpt in organization"), 429, true, false,
                "Source error 429 (rate_limit_exceeded)"),
            Arguments.of(200, error("rate_limit_error", null, "slow down"), 429, true, false, "Source error 429 (rate_limit_error)"),
            // out of money -> 402, although OpenAI sends it as a 429
            Arguments.of(429, error("insufficient_quota", "insufficient_quota",
                "You exceeded your current quota, please check your plan and billing details."), 402, true, false,
                "Source error 402 (insufficient_quota)"),
            Arguments.of(403, error("new_api_error", "insufficient_user_quota", "user quota is not enough"), 402, true, false,
                "Source error 402 (insufficient_user_quota)"),
            Arguments.of(400, error("invalid_request_error", "billing_hard_limit_reached", "Billing hard limit has been reached"), 402,
                true, false, "Source error 402 (billing_hard_limit_reached)"),
            Arguments.of(429, error(null, null, "You exceeded your current quota"), 402, true, false, "Source error 402"),
            // DeepSeek says a spent balance with a 402 and names the type of everything it refuses invalid_request_error
            Arguments.of(402, error("unknown_error", "invalid_request_error", "Insufficient Balance"), 402, true, false,
                "Source error 402 (invalid_request_error)"),
            Arguments.of(402, error("invalid_request_error", null, "Insufficient account balance, please recharge"), 402, true, false,
                "Source error 402 (invalid_request_error)"),
            Arguments.of(402, error("invalid_request_error", null, "\u4f59\u989d\u4e0d\u8db3\uff0c\u8bf7\u5145\u503c"), 402, true, false,
                "Source error 402 (invalid_request_error)"),
            Arguments.of(400, error(null, null, "\u989d\u5ea6\u4e0d\u8db3"), 402, true, false, "Source error 402"),
            Arguments.of(200, error(null, null, "Your balance is not enough"), 402, true, false, "Source error 402"),
            // invalid request -> 400
            Arguments.of(400, error("invalid_request_error", null, "Unsupported parameter: top_k"), 400, false, false,
                "Source error 400 (invalid_request_error)"),
            Arguments.of(422, error("invalid_request_error", "unsupported_value", "bad value"), 400, false, false,
                "Source error 400 (unsupported_value)"),
            // the context window -> 400 and the marker
            Arguments.of(400, error("invalid_request_error", "context_length_exceeded",
                "This model's maximum context length is 8192 tokens."), 400, false, true, "Source error 400 (context_length_exceeded)"),
            Arguments.of(400, error("invalid_request_error", null, "prompt is too long: 250000 tokens > 200000 maximum"), 400, false, true,
                "Source error 400 (invalid_request_error)"),
            Arguments.of(413, error("invalid_request_error", null, "Input is too long for requested model"), 400, false, true,
                "Source error 400 (invalid_request_error)"),
            Arguments.of(500, error("server_error", null, "This model's Maximum Context Length is 4096"), 400, false, true,
                "Source error 400 (server_error)"),
            // Anthropic's words for a prompt that fits but not beside max_tokens, as a relay wraps them and as the API sends them: a 400
            // like any other, marked as another kind (isBesideOutput) and not as a refusal of the length (see the tests below)
            Arguments.of(400, error("invalid_request_error", null, ANTHROPIC_BESIDE_OUTPUT), 400, false, false,
                "Source error 400 (invalid_request_error)"),
            Arguments.of(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":" + quote(ANTHROPIC_BESIDE_OUTPUT) + "}}",
                400, false, false, "Source error 400 (invalid_request_error)"),
            // a model the relay does not serve -> 404, whatever came with it
            Arguments.of(404, error("invalid_request_error", "model_not_found", "The model `claude-x` does not exist"), 404, false, false,
                "Source error 404 (model_not_found)"),
            Arguments.of(503, error("new_api_error", null, "No available channel for model claude-x under group default"), 404, false, false,
                "Source error 404 (new_api_error)"),
            Arguments.of(403, error("permission_error", null, "You do not have access to the model `claude-x`"), 404, false, false,
                "Source error 404 (permission_error)"),
            Arguments.of(400, error("invalid_request_error", null, "unsupported model: claude-x"), 404, false, false,
                "Source error 404 (invalid_request_error)"),
            Arguments.of(422, error(null, null, "The model claude-x does not exist or you do not have access to it."), 404, false, false,
                "Source error 404"),
            // authentication -> 401, the code is read before the type
            Arguments.of(401, error("invalid_request_error", "invalid_api_key", "Incorrect API key provided: sk-secret"), 401, false, false,
                "Source error 401 (invalid_api_key)"),
            Arguments.of(200, error("authentication_error", null, "invalid x-api-key"), 401, false, false,
                "Source error 401 (authentication_error)"),
            // permission -> 403
            Arguments.of(403, error("permission_error", null, "Your account may not use this endpoint"), 403, false, false,
                "Source error 403 (permission_error)"),
            Arguments.of(200, error("access_denied", null, "denied"), 403, false, false, "Source error 403 (access_denied)"),
            // overloaded and server errors -> 503, recoverable
            Arguments.of(529, error("overloaded_error", null, "Overloaded"), 503, true, false, "Source error 503 (overloaded_error)"),
            Arguments.of(500, error("server_error", null, "The server had an error while processing your request"), 503, true, false,
                "Source error 503 (server_error)"),
            Arguments.of(200, error("api_error", null, "Internal server error"), 503, true, false, "Source error 503 (api_error)"),
            Arguments.of(502, error("service_unavailable", null, "try later"), 503, true, false, "Source error 503 (service_unavailable)"),
            // a name that only contains api_error is not api_error: new-api says it for everything
            Arguments.of(200, error("new_api_error", null, "something"), 502, true, false, "Source error 502 (new_api_error)")
        );
    }

    @ParameterizedTest(name = "[{index}] http {0} -> {2}")
    @MethodSource("theKindsOfError")
    void eachKindOfErrorGetsItsStatusAsAResponse(int httpStatus, String body, int status, boolean recoverable, boolean contextExceeded,
                                                 String message) {
        RelayErrorException error = map(httpStatus, body);

        assertThat(error.getStatusCode()).isEqualTo(status);
        assertThat(error.isRecoverable()).isEqualTo(recoverable);
        assertThat(error.isContextExceeded()).isEqualTo(contextExceeded);
        assertThat(error.getMessage()).isEqualTo(message);
        assertThat(error.isGenerationStarted()).isFalse();
    }

    @ParameterizedTest(name = "[{index}] in-band -> {2}")
    @MethodSource("theKindsOfError")
    void theSameErrorInsideASuccessfulResponseGetsTheSameStatus(int httpStatus, String body, int status, boolean recoverable,
                                                                boolean contextExceeded, String message) {
        RelayErrorException error = mapper.fromInBand(JSON.readTree(body).get("error"), "account 7", null);

        assertThat(error.getStatusCode()).isEqualTo(status);
        assertThat(error.isRecoverable()).isEqualTo(recoverable);
        assertThat(error.isContextExceeded()).isEqualTo(contextExceeded);
        assertThat(error.getMessage()).isEqualTo(message);
        assertThat(error.isGenerationStarted()).isFalse();
    }

    // ---------------------------------------------------------------- a status that says more than the type of the body

    /**
     * Relays call the type of what they refuse {@code invalid_request_error}: with a 400 that is a request no relay can
     * answer and the next source may, with a 402 or a 429 it is a relay that cannot now (out of money, or busy), and
     * the sibling relay and the cooldown depend on telling the two apart.
     */
    @Test
    void aPaymentRequiredOrTooManyRequestsStatusIsNotAnInvalidRequestBecauseTheBodyCallsItOne() {
        RelayErrorException money = map(402, error("invalid_request_error", null, "Please top up your account to continue"));
        assertThat(money.getStatusCode()).isEqualTo(402);
        assertThat(money.isRecoverable()).isTrue();
        assertThat(money.getMessage()).isEqualTo("Source error 402 (invalid_request_error)");

        for (String body : List.of(
            error("invalid_request_error", null, "Too many requests"),
            error("unknown_error", "invalid_request_error", "Slow down"),
            error("invalid_request_error", "bad_request", "Try later"))) {
            RelayErrorException busy = map(429, body);

            assertThat(busy.getStatusCode()).as(body).isEqualTo(429);
            assertThat(busy.isRecoverable()).as(body).isTrue();
            assertThat(busy.isContextExceeded()).as(body).isFalse();
        }
    }

    /**
     * A relay that always answers 200 can only say it in the body: the number in {@code error.code} is the status it
     * would have used, and it says the same as the status of a response.
     */
    @Test
    void aPaymentRequiredOrTooManyRequestsCodeInASuccessfulResponseIsNotAnInvalidRequestEither() throws Exception {
        for (String error : List.of(
            "{\"type\":\"invalid_request_error\",\"code\":429,\"message\":\"Concurrency limit exceeded for this key\"}",
            "{\"type\":\"invalid_request_error\",\"code\":\"429\",\"message\":\"Concurrency limit exceeded for this key\"}",
            "{\"type\":\"invalid_request_error\",\"code\":402,\"message\":\"Please top up to continue\"}",
            "{\"type\":\"invalid_request_error\",\"code\":\"402\",\"message\":\"Please top up to continue\"}")) {
            int expected = error.contains("429") ? 429 : 402;

            RelayErrorException inBand = mapper.fromInBand(JSON.readTree(error), "account 7", null);
            RelayErrorException asResponse = map(200, "{\"error\":" + error + "}");

            assertThat(inBand.getStatusCode()).as(error).isEqualTo(expected);
            assertThat(inBand.isRecoverable()).as(error).isTrue();
            assertThat(inBand.isContextExceeded()).as(error).isFalse();
            assertThat(inBand.getMessage()).as(error).isEqualTo("Source error " + expected + " (invalid_request_error)");
            assertThat(asResponse.getStatusCode()).as(error).isEqualTo(expected);
        }
        // Another number is not a status that says more: a 400, or a business code, leaves the type to decide.
        assertThat(mapper.fromInBand(JSON.readTree("{\"type\":\"invalid_request_error\",\"code\":400,\"message\":\"x\"}"), null, null)
            .getStatusCode()).isEqualTo(400);
        assertThat(mapper.fromInBand(JSON.readTree("{\"type\":\"invalid_request_error\",\"code\":4290,\"message\":\"x\"}"), null, null)
            .getStatusCode()).isEqualTo(400);
        // And a named kind still wins over the number, as it does over a response status.
        assertThat(mapper.fromInBand(JSON.readTree("{\"type\":\"authentication_error\",\"code\":429,\"message\":\"x\"}"), null, null)
            .getStatusCode()).isEqualTo(401);
    }

    @Test
    void theSameBodiesWithAFourHundredOrNoStatusAreStillInvalidRequests() {
        for (String body : List.of(
            error("invalid_request_error", null, "Please top up your account to continue"),
            error("invalid_request_error", null, "Too many requests"))) {
            assertThat(map(400, body).getStatusCode()).as(body).isEqualTo(400);
            assertThat(mapper.fromInBand(JSON.readTree(body).get("error"), "account 7", null).getStatusCode()).as(body).isEqualTo(400);
        }
    }

    @Test
    void anotherKindOfNameStillWinsOverTheStatusThatSaysMore() {
        // A 429 whose body says the credentials are wrong is a credentials error: only the generic kind gives way.
        assertThat(map(429, error("authentication_error", null, "invalid x-api-key")).getStatusCode()).isEqualTo(401);
        assertThat(map(402, error("rate_limit_error", null, "slow down")).getStatusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- the status the relay itself used

    @Test
    void aNumericCodeFromFourHundredToFiveNinetyNineIsTheStatus() {
        // OpenRouter and LiteLLM put the status into error.code, as a number or as a string.
        assertThat(map(200, "{\"error\":{\"code\":429,\"message\":\"slow\"}}").getStatusCode()).isEqualTo(429);
        assertThat(map(200, "{\"error\":{\"code\":\"503\",\"type\":\"None\",\"message\":\"x\"}}").getStatusCode()).isEqualTo(503);
        assertThat(mapper.fromInBand(JSON.readTree("{\"code\":408,\"message\":\"x\"}"), null, null).getStatusCode()).isEqualTo(408);
        // Out of range: a business code is not a status, and the response status applies instead.
        assertThat(map(409, "{\"error\":{\"code\":40001,\"message\":\"x\"}}").getStatusCode()).isEqualTo(409);
        assertThat(map(200, "{\"error\":{\"code\":200,\"message\":\"x\"}}").getStatusCode()).isEqualTo(502);
        assertThat(map(200, "{\"error\":{\"code\":99,\"message\":\"x\"}}").getStatusCode()).isEqualTo(502);
        assertThat(map(200, "{\"error\":{\"code\":4290,\"message\":\"x\"}}").getStatusCode()).isEqualTo(502);
        // A named kind comes first: the number is the relay's guess.
        assertThat(map(200, "{\"error\":{\"code\":500,\"type\":\"rate_limit_error\",\"message\":\"x\"}}").getStatusCode()).isEqualTo(429);
    }

    @ParameterizedTest(name = "[{index}] http {0}")
    @MethodSource("statusesWithoutAName")
    void anErrorWithoutAKindKeepsItsHttpStatus(int httpStatus, int status, boolean recoverable) {
        for (String body : List.of("", "<html><body>Bad gateway</body></html>", "{\"error\":{\"message\":\"x\"}}", "plain text")) {
            RelayErrorException error = map(httpStatus, body);

            assertThat(error.getStatusCode()).as(body).isEqualTo(status);
            assertThat(error.isRecoverable()).as(body).isEqualTo(recoverable);
            assertThat(error.isContextExceeded()).isFalse();
        }
    }

    static Stream<Arguments> statusesWithoutAName() {
        return Stream.of(
            Arguments.of(400, 400, false), Arguments.of(401, 401, false), Arguments.of(402, 402, true),
            Arguments.of(403, 403, false), Arguments.of(404, 404, false), Arguments.of(408, 408, false),
            Arguments.of(409, 409, false), Arguments.of(413, 413, false), Arguments.of(422, 422, false),
            Arguments.of(429, 429, true), Arguments.of(500, 500, true), Arguments.of(502, 502, true),
            Arguments.of(503, 503, true), Arguments.of(504, 504, true), Arguments.of(520, 520, true)
        );
    }

    @Test
    void anythingElseIsARecoverable502() {
        for (RelayErrorException error : List.of(
            map(200, ""),
            map(200, "{\"error\":{\"message\":\"x\",\"type\":\"weird_thing\"}}"),
            map(302, "moved"),
            map(100, null),
            mapper.fromInBand(null, null, null),
            mapper.fromInBand(JSON.readTree("{}"), null, null),
            mapper.fromInBand(JSON.readTree("\"just words\""), null, null)
        )) {
            assertThat(error.getStatusCode()).isEqualTo(502);
            assertThat(error.isRecoverable()).isTrue();
            assertThat(error.isContextExceeded()).isFalse();
            assertThat(error.isGenerationStarted()).isFalse();
        }
        assertThat(map(200, "").getMessage()).isEqualTo("Source error 502");
    }

    // ---------------------------------------------------------------- the shapes of the body

    @Test
    void theErrorMayBeAStringTheTopOfTheBodyOrTheFirstOfAList() {
        assertThat(map(200, "{\"error\":\"model_not_found\"}").getStatusCode()).isEqualTo(404);
        // At the top: {"message", "type", "code"} without the wrapper.
        RelayErrorException top = map(200, "{\"message\":\"x\",\"type\":\"rate_limit_error\"}");
        assertThat(top.getStatusCode()).isEqualTo(429);
        assertThat(top.getMessage()).isEqualTo("Source error 429 (rate_limit_error)");
        // Google style: a list with the error in it.
        assertThat(map(200, "[{\"error\":{\"type\":\"overloaded_error\",\"message\":\"x\"}}]").getStatusCode()).isEqualTo(503);
        // Another key for the text: the whole body is looked at.
        assertThat(map(500, "{\"detail\":\"No available channel for model claude-x\"}").getStatusCode()).isEqualTo(404);
        assertThat(map(500, "{\"error\":{\"detail\":\"Maximum context length exceeded\"}}").isContextExceeded()).isTrue();
        // A message that is not a string, or an error that is null.
        assertThat(map(429, "{\"error\":{\"message\":{\"a\":1},\"type\":\"rate_limit_error\"}}").getStatusCode()).isEqualTo(429);
        assertThat(map(429, "{\"error\":null}").getStatusCode()).isEqualTo(429);
    }

    @Test
    void aBodyThatIsNotJsonIsReadAsText() {
        assertThat(map(500, "Error: no available channel for model claude-x").getStatusCode()).isEqualTo(404);
        assertThat(map(502, "<html><title>502 Bad Gateway</title>").getStatusCode()).isEqualTo(502);
        assertThat(map(400, "{\"error\": {").getStatusCode()).isEqualTo(400);
        assertThat(map(400, "[1,2").getStatusCode()).isEqualTo(400);
        assertThat(map(500, "123").getStatusCode()).isEqualTo(500);
        assertThat(map(500, "[\"prompt is too long\"]").isContextExceeded()).isTrue();
    }

    @Test
    void aVeryLongBodyIsStillReadFromItsStart() {
        String huge = "x".repeat(200_000);
        RelayErrorException error = map(500, error("server_error", null, huge));

        // Cut inside the string, so it is not JSON any more; the text has no verdict and the status stays.
        assertThat(error.getStatusCode()).isEqualTo(500);
        assertThat(map(429, "maximum context length " + huge).isContextExceeded()).isTrue();
        assertThat(logged.list).hasSize(2);
        for (ILoggingEvent event : logged.list) {
            assertThat(event.getFormattedMessage().getBytes(StandardCharsets.UTF_8).length).isLessThan(RelayErrorMapper.LOG_LIMIT_BYTES + 200);
        }
    }

    @Test
    void aRelayThatIsOutOfCreditIsOutOfMoneyWhateverItCallsIt() {
        // How Anthropic says it, and so every relay that passes its answer on: a 400, and "billing" only in the text.
        RelayErrorException anthropic = map(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
            + "\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.\"}}");
        assertThat(anthropic.getStatusCode()).isEqualTo(402);
        assertThat(anthropic.isRecoverable()).isTrue();
        assertThat(anthropic.getMessage()).isEqualTo("Source error 402 (invalid_request_error)");

        assertThat(map(400, error("invalid_request_error", null, "Insufficient credits. Add more using https://openrouter.ai/credits"))
            .getStatusCode()).isEqualTo(402);
        assertThat(map(403, error(null, null, "You are out of credits")).getStatusCode()).isEqualTo(402);
        assertThat(map(400, error("invalid_request_error", null, "Please purchase credits to continue")).getStatusCode()).isEqualTo(402);
        // A bad request that only mentions credit is a bad request.
        assertThat(map(400, error("invalid_request_error", null, "The credit card field is invalid")).getStatusCode()).isEqualTo(400);
    }

    @Test
    void aTimeoutKeepsItsStatusWhateverItsBodyCallsIt() {
        // A gateway in front of a relay says 504 and the relay's wrapper says server_error, which is a 503 otherwise.
        for (int status : List.of(408, 504, 524)) {
            RelayErrorException error = map(status, error("server_error", null, "upstream request timeout"));
            assertThat(error.getStatusCode()).as("status %s", status).isEqualTo(status);
            assertThat(RelayErrorMapper.isTimeoutStatus(error.getStatusCode())).isTrue();
        }
        assertThat(map(504, "<html><title>504 Gateway Time-out</title>").getStatusCode()).isEqualTo(504);
        assertThat(map(524, "").getStatusCode()).isEqualTo(524);
        // Its own words about money or context still decide first.
        assertThat(map(504, error("insufficient_quota", null, "x")).getStatusCode()).isEqualTo(402);
        assertThat(map(408, error("invalid_request_error", null, "prompt is too long")).getStatusCode()).isEqualTo(400);

        // Inside a successful response, by name or by number: the same timeout.
        assertThat(mapper.fromInBand(JSON.readTree("{\"type\":\"timeout_error\",\"message\":\"x\"}"), null, null).getStatusCode()).isEqualTo(504);
        assertThat(mapper.fromInBand(JSON.readTree("{\"code\":\"gateway_timeout\",\"message\":\"x\"}"), null, null).getStatusCode()).isEqualTo(504);
        assertThat(mapper.fromInBand(JSON.readTree("{\"code\":504,\"message\":\"x\"}"), null, null).getStatusCode()).isEqualTo(504);
        assertThat(RelayErrorMapper.isTimeoutStatus(500)).isFalse();
        assertThat(RelayErrorMapper.isTimeoutStatus(503)).isFalse();
    }

    // ---------------------------------------------------------------- the message

    @Test
    void theMessageHasTheCodeElseTheTypeAndOnlyIfItIsPlainWords() {
        // The code first.
        assertThat(map(429, error("requests", "rate_limit_exceeded", "x")).getMessage()).isEqualTo("Source error 429 (rate_limit_exceeded)");
        // Upper case is written in lower case.
        assertThat(map(429, error("Rate_Limit_Error", null, "x")).getMessage()).isEqualTo("Source error 429 (rate_limit_error)");
        // A code that is not plain words gives way to the type; both not plain gives none.
        assertThat(map(429, error("rate_limit_error", "429", "x")).getMessage()).isEqualTo("Source error 429 (rate_limit_error)");
        assertThat(map(500, error("rate_limit_error", "relay.example.com/v1", "x")).getMessage()).isEqualTo("Source error 429 (rate_limit_error)");
        assertThat(map(500, error("a1", "b-c", "x")).getMessage()).isEqualTo("Source error 500");
        assertThat(map(500, error("None", "null", "x")).getMessage()).isEqualTo("Source error 500");
        // Forty letters at most.
        String forty = "a".repeat(40);
        assertThat(map(500, error(forty, null, "x")).getMessage()).isEqualTo("Source error 500 (" + forty + ")");
        assertThat(map(500, error(forty + "a", null, "x")).getMessage()).isEqualTo("Source error 500");
        // Not a token that has spaces or punctuation, although "rate limit" still says what it says.
        assertThat(map(500, error("rate limit", null, "x")).getMessage()).isEqualTo("Source error 429");
        assertThat(map(500, error("http://relay.example.com", null, "x")).getMessage()).isEqualTo("Source error 500");
        assertThat(map(500, error("a\nb", null, "x")).getMessage()).isEqualTo("Source error 500");
    }

    @Test
    void nothingTheRelayWroteIsInTheException() {
        for (String body : List.of(
            error("rate_limit_error", "rate_limit_exceeded", LEAK),
            error("invalid_request_error", "context_length_exceeded", "prompt is too long: " + LEAK),
            error("invalid_request_error", "model_not_found", LEAK),
            error("server_error", null, LEAK),
            error(LEAK, LEAK, LEAK),
            "{\"error\":{\"message\":\"" + LEAK + "\",\"code\":429}}",
            "{\"error\":\"" + LEAK + "\"}",
            "{\"detail\":\"" + LEAK + "\"}",
            LEAK,
            "<html>" + LEAK + "</html>"
        )) {
            for (RelayErrorException error : List.of(map(500, body), mapper.fromResponse(429, body, "30", "account 7", null))) {
                assertNoRelayWords(error);
            }
        }
        assertNoRelayWords(mapper.fromInBand(JSON.readTree(error("server_error", "x", LEAK)).get("error"), LEAK + " as source", null));
        assertNoRelayWords(mapper.fromInBand(JSON.readTree("\"" + LEAK + "\""), null, null));
    }

    private static void assertNoRelayWords(Throwable error) {
        for (String word : List.of("relay.internal", "example.com", "https", "sk-secret", "prompt", "taxes", "user's", "html")) {
            assertThat(String.valueOf(error.getMessage())).doesNotContain(word);
            assertThat(error.toString()).doesNotContain(word);
        }
        assertThat(error.getCause()).isNull();
        assertThat(error.getSuppressed()).isEmpty();
    }

    @Test
    void theRetryAfterOfTheResponseIsKept() {
        assertThat(UpstreamException.retryAfterSeconds(mapper.fromResponse(429, error("rate_limit_error", null, "x"), "17", null, null)))
            .isEqualTo(17);
        // Also when the verdict changed the status.
        assertThat(UpstreamException.retryAfterSeconds(mapper.fromResponse(429, error("insufficient_quota", null, "x"), "30", null, null)))
            .isEqualTo(30);
        assertThat(UpstreamException.retryAfterSeconds(mapper.fromResponse(429, "", null, null, null))).isZero();
        assertThat(UpstreamException.retryAfterSeconds(mapper.fromResponse(429, "", "not a number", null, null))).isZero();
        assertThat(UpstreamException.retryAfterSeconds(mapper.fromInBand(JSON.readTree("{\"type\":\"overloaded_error\"}"), null, null))).isZero();
    }

    // ---------------------------------------------------------------- the marker

    @Test
    void theContextMarkerIsFoundThroughWrappingAndOnlyOnAContextError() {
        RelayErrorException context = map(400, error("invalid_request_error", "context_length_exceeded", "x"));
        RelayErrorException other = map(400, error("invalid_request_error", null, "x"));

        assertThat(RelayErrorException.contextExceeded(context)).isTrue();
        assertThat(RelayErrorException.contextExceeded(new IllegalStateException("wrapped", new RuntimeException("deeper", context)))).isTrue();
        assertThat(RelayErrorException.contextExceeded(other)).isFalse();
        assertThat(RelayErrorException.contextExceeded(new UpstreamException(400, "plain", false))).isFalse();
        assertThat(RelayErrorException.contextExceeded(new RelayAbortedAfterDispatchException("cut"))).isFalse();
        assertThat(RelayErrorException.contextExceeded(null)).isFalse();
        // It does not mark other statuses, and it is not the status 400 alone.
        assertThat(map(400, "").isContextExceeded()).isFalse();
        assertThat(map(413, "").isContextExceeded()).isFalse();
    }

    /**
     * Anthropic's refusal of a prompt that fits but not beside its output says nothing of the prompt alone, so it is no refusal of the
     * length by itself: it is a 400 of the converted request with its own marker. A caller that chose the output limit reads it as a
     * refusal of the length, and gets the same error as one of the words above.
     */
    @Test
    void aPromptThatDoesNotFitBesideItsOutputIsNoRefusalOfTheLengthUntilTheCallerThatChoseTheOutputSaysSo() {
        RelayErrorException beside = map(400, error("invalid_request_error", null, ANTHROPIC_BESIDE_OUTPUT));

        assertThat(beside.isBesideOutput()).isTrue();
        assertThat(beside.isContextExceeded()).isFalse();
        assertThat(RelayErrorException.contextExceeded(beside)).isFalse();

        RelayErrorException asLength = beside.asContextExceeded();
        assertThat(asLength.isContextExceeded()).isTrue();
        assertThat(asLength.isBesideOutput()).isFalse();
        assertThat(RelayErrorException.contextExceeded(asLength)).isTrue();
        assertThat(asLength.getStatusCode()).isEqualTo(beside.getStatusCode());
        assertThat(asLength.isRecoverable()).isEqualTo(beside.isRecoverable());
        assertThat(asLength.getMessage()).isEqualTo(beside.getMessage());
        assertThat(UpstreamException.retryAfterSeconds(asLength)).isEqualTo(UpstreamException.retryAfterSeconds(beside));
    }

    @Test
    void theWordsOfAnthropicMarkOnlyA400AndNeverAKindOfErrorThatAStatusOrAQuotaWordDecided() {
        // A refusal of the length in the words of vLLM is the refusal of the length, whatever else its text says.
        RelayErrorException length = map(400, error("invalid_request_error", null, ANTHROPIC_BESIDE_OUTPUT + " prompt is too long"));
        assertThat(length.isContextExceeded()).isTrue();
        assertThat(length.isBesideOutput()).isFalse();
        // The status of the response that says more than the type (a 429, a 402) and the words of an empty balance decide as before.
        assertThat(map(429, error("invalid_request_error", null, ANTHROPIC_BESIDE_OUTPUT)).isBesideOutput()).isFalse();
        assertThat(map(429, error("invalid_request_error", null, ANTHROPIC_BESIDE_OUTPUT)).getStatusCode()).isEqualTo(429);
        assertThat(map(400, error("invalid_request_error", null, "credit balance is too low; " + ANTHROPIC_BESIDE_OUTPUT)).isBesideOutput()).isFalse();
        assertThat(map(400, error("invalid_request_error", null, "Unsupported parameter: top_k")).isBesideOutput()).isFalse();
        // In a successful response it is the same.
        RelayErrorException inBand = mapper.fromInBand(JSON.readTree(error("invalid_request_error", null, ANTHROPIC_BESIDE_OUTPUT)).get("error"), "account 7", null);
        assertThat(inBand.isBesideOutput()).isTrue();
        assertThat(inBand.isContextExceeded()).isFalse();
    }

    // ---------------------------------------------------------------- the server log

    @Test
    void theRelaysWordsGoToTheLogAtWarnAndOnlyThere() {
        RelayErrorException error = map(429, error("insufficient_quota", "insufficient_quota", LEAK));

        assertThat(logged.list).hasSize(1);
        ILoggingEvent event = logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
            .contains("account 7", "httpStatus=429", "mappedStatus=402", "relay.internal.example.com", "taxes");
        // The words are the relay's to write, a key among them is not the log's to keep.
        assertThat(event.getFormattedMessage()).doesNotContain("sk-secret-0123").contains(RelayErrorMapper.REDACTED);
        assertThat(error.getMessage()).doesNotContain("relay.internal");
    }

    @Test
    void anErrorInsideASuccessfulResponseIsLoggedTheSameWay() {
        mapper.fromInBand(JSON.readTree(error("server_error", null, LEAK)).get("error"), "account 9", null);
        mapper.fromInBand(JSON.readTree("\"" + LEAK + "\""), "account 9", null);

        assertThat(logged.list).hasSize(2);
        for (ILoggingEvent event : logged.list) {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("account 9", "relay.internal.example.com").doesNotContain("sk-secret-0123");
        }
        assertThat(logged.list.get(0).getFormattedMessage()).contains("mappedStatus=503");
        assertThat(logged.list.get(1).getFormattedMessage()).contains("mappedStatus=502");
    }

    @Test
    void theKeyOfTheAccountAndWhatLooksLikeACredentialNeverReachTheLog() {
        String key = "relay-key-9f8e7d6c5b4a";
        String body = error("authentication_error", null, "Incorrect key " + key + " for relay.internal.example.com; "
            + "got Authorization: Bearer abc.DEF-123_xyz and api_key=hunter2hunter2 and sk-ant-api03-AbCdEf_123456-xyz and "
            + "sk-proj-0123456789abcdef and " + googleKeyShape() + " and token=\"s3cr3t-value\" and a hash "
            + "0123456789abcdef0123456789abcdef0123456789abcdef");

        mapper.fromResponse(401, body, null, "account 7", key);
        mapper.fromInBand(JSON.readTree(body).get("error"), "account 7", key);

        assertThat(logged.list).hasSize(2);
        for (ILoggingEvent event : logged.list) {
            String line = event.getFormattedMessage();
            assertThat(line).doesNotContain(key, "abc.DEF", "hunter2", "AbCdEf", "sk-proj", "AIzaSy", "s3cr3t", "0123456789abcdef0123456789abcdef");
            // The words around them are still there to read.
            assertThat(line).contains("Incorrect key", "relay.internal.example.com", "got Authorization", "and a hash", RelayErrorMapper.REDACTED);
        }
    }

    /**
     * An error inside a successful response is bounded by nothing but the size of the answer. The log holds two kilobytes of
     * it, and finding the credentials in sixteen megabytes of words took seconds of the thread that reads the answer.
     */
    @Test
    void aHugeErrorIsCutBeforeItIsScannedForCredentialsAndStillLogsItsStart() {
        String words = ("abcdefghij".repeat(3) + "abcdefghi" + " ").repeat(400_000);
        ObjectNode error = JSON.createObjectNode();
        error.put("message", "context length exceeded " + words);
        error.put("type", "invalid_request_error");

        long started = System.nanoTime();
        RelayErrorException mapped = mapper.fromInBand(error, "account 5", "relay-key-9f8e7d6c5b4a");
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("milliseconds for sixteen million characters").isLessThan(1_000);
        assertThat(mapped.getStatusCode()).isEqualTo(400);
        assertThat(mapped.isContextExceeded()).isTrue();
        assertThat(logged.list).hasSize(1);
        String line = logged.list.get(0).getFormattedMessage();
        assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThan(RelayErrorMapper.LOG_LIMIT_BYTES + 300);
        assertThat(line).contains("account 5", "context length exceeded", "...");
    }

    @Test
    void anErrorStatusBodyAndAnErrorTextThatAreLongAreCutTheSameWay() {
        String words = ("abcdefghij".repeat(3) + "abcdefghi" + " ").repeat(300_000);

        long started = System.nanoTime();
        mapper.fromResponse(500, words, null, "account 5", null);
        mapper.fromInBand(JSON.readTree(JSON.writeValueAsString(words)), "account 5", null);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(1_000);
        assertThat(logged.list).hasSize(2);
        for (ILoggingEvent event : logged.list) {
            assertThat(event.getFormattedMessage().getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(RelayErrorMapper.LOG_LIMIT_BYTES + 300);
        }
    }

    @Test
    void whatIsPastTheStartThatIsScannedIsNotLoggedAndAKeyIsNeverLoggedInPart() {
        String key = "f3a91c0d7b2e4c5a9e8d6b1a2c3d4e5f";
        // Credential-shaped words shrink to a mark each, so that the end of what was scanned is inside the two kilobytes.
        String shrinking = ("b".repeat(100) + " ").repeat(78);
        // Then a run without white space (masked), and the key, which begins before the end of the scan and ends after it.
        String text = shrinking + "c".repeat(300) + ":" + key + " and the rest of the error";

        String logged = RelayErrorMapper.forLog(text, key);

        assertThat(logged).doesNotContain(key, "f3a91c", "the rest").endsWith("...");
        assertThat(logged).startsWith(RelayErrorMapper.REDACTED);
        // A text that fits is logged whole, as it was.
        assertThat(RelayErrorMapper.forLog("short " + key + " text", key)).isEqualTo("short [redacted] text");
        assertThat(RelayErrorMapper.forLog("", null)).isEmpty();
    }

    @Test
    void theCutOfALongTextIsNeverInTheMiddleOfACharacter() {
        // Credential-shaped words shrink, so that the cut is inside the two kilobytes that are logged; the emoji are pairs of
        // chars, and the pair that the end of the scan would split is left out whole.
        String shrinking = ("b".repeat(100) + " ").repeat(78);
        String text = shrinking + "a" + "\uD83D\uDE00".repeat(2_000);

        String logged = RelayErrorMapper.forLog(text, null);

        assertThat(logged).endsWith("\uD83D\uDE00...");
        for (int i = 0; i < logged.length(); i++) {
            if (Character.isHighSurrogate(logged.charAt(i))) {
                assertThat(Character.isLowSurrogate(logged.charAt(i + 1))).isTrue();
                i++;
            } else {
                assertThat(Character.isLowSurrogate(logged.charAt(i))).isFalse();
            }
        }
    }

    @Test
    void theCutOfALongTextFallsOnWhiteSpaceWhenThereIsSomeNearTheEnd() {
        // A credential-shaped word is masked only from forty characters; the twelve of this one that the end of the scan
        // would let through are not logged, because the cut is made before the word.
        String shrinking = ("b".repeat(100) + " ").repeat(78);
        String text = shrinking + "q ".repeat(151) + "K".repeat(100) + " the end";

        String logged = RelayErrorMapper.forLog(text, null);

        assertThat(logged).doesNotContain("KKK", "the end").endsWith("q ...");
        // With no white space near the cut there is nothing to fall back to, and a long word is masked as it is.
        assertThat(RelayErrorMapper.forLog(shrinking + "K".repeat(500), null)).doesNotContain("KKK");
    }

    @Test
    void redactingMasksCredentialsWithoutEatingOrdinaryWords() {
        assertThat(RelayErrorMapper.redact("", "secret-key")).isEmpty();
        assertThat(RelayErrorMapper.redact("nothing here", null)).isEqualTo("nothing here");
        assertThat(RelayErrorMapper.redact("model claude-sonnet-4.5 max_tokens: 4096 prompt_tokens=12 task-force", "k"))
            .as("a name or a count is not a credential, and a key shorter than six characters is not masked")
            .isEqualTo("model claude-sonnet-4.5 max_tokens: 4096 prompt_tokens=12 task-force");
        assertThat(RelayErrorMapper.redact("key abcdef123 and abcdef123", "abcdef123")).isEqualTo("key [redacted] and [redacted]");
        assertThat(RelayErrorMapper.redact("Bearer   tok_123 then more", null)).isEqualTo("[redacted] then more");
        assertThat(RelayErrorMapper.redact("{\"api-key\": \"abc123\", \"x\": 1}", null)).isEqualTo("{\"[redacted]\", \"x\": 1}");
        // A uuid and an ordinary long word stay; a run of forty and more does not.
        assertThat(RelayErrorMapper.redact("request 123e4567-e89b-12d3-a456-426614174000 failed", null))
            .isEqualTo("request 123e4567-e89b-12d3-a456-426614174000 failed");
        assertThat(RelayErrorMapper.redact("blob " + "a".repeat(40) + "== end", null)).isEqualTo("blob [redacted] end");
    }

    @Test
    void theLoggedTextIsAtMostTwoKilobytesOnOneLine() {
        String body = "line one\nline two\r\n\tindented " + "é".repeat(5000);

        map(500, body);

        String line = logged.list.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        String text = line.substring(line.indexOf("relayText=") + "relayText=".length());
        assertThat(text).endsWith("...");
        assertThat(text.substring(0, text.length() - 3).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(2000);
    }

    @Test
    void clippingKeepsWholeCharactersAndFlattensControlCharacters() {
        assertThat(RelayErrorMapper.clip("")).isEmpty();
        assertThat(RelayErrorMapper.clip("short")).isEqualTo("short");
        assertThat(RelayErrorMapper.clip("a\nb\r\nc\td\u0000e\u001b[31m")).isEqualTo("a b  c d e [31m");
        // Exactly the limit is kept whole; one byte more is cut, with a mark.
        assertThat(RelayErrorMapper.clip("a".repeat(2048))).isEqualTo("a".repeat(2048));
        assertThat(RelayErrorMapper.clip("a".repeat(2049))).isEqualTo("a".repeat(2048) + "...");
        // Two-byte characters: 1024 fit.
        assertThat(RelayErrorMapper.clip("é".repeat(1025))).isEqualTo("é".repeat(1024) + "...");
        // Three-byte characters: 682 fit (2046 bytes), the next would be 2049.
        assertThat(RelayErrorMapper.clip("好".repeat(700))).isEqualTo("好".repeat(682) + "...");
        // A four-byte character is never split into its two surrogates: after 2046 bytes the next one does not fit.
        String emoji = "😀";
        assertThat(RelayErrorMapper.clip("a".repeat(2046) + emoji + emoji)).isEqualTo("a".repeat(2046) + "...");
        assertThat(RelayErrorMapper.clip("a".repeat(2044) + emoji + emoji)).isEqualTo("a".repeat(2044) + emoji + "...");
    }

    @Test
    void aNullSourceAndABodyThatIsNullDoNotBreakTheMapper() {
        assertThat(mapper.fromResponse(500, null, null, null, null).getStatusCode()).isEqualTo(500);
        assertThat(mapper.fromInBand(null, null, null).getStatusCode()).isEqualTo(502);
        assertThat(logged.list).hasSize(2);
    }

    // ---------------------------------------------------------------- as the accumulator's error hook

    @Test
    void itIsWhatTheAccumulatorAsksForAnErrorInsideASuccessfulResponse() {
        ChatCompletionAccumulator json = new ChatCompletionAccumulator(JSON, error -> mapper.fromInBand(error, "account 3", null));

        assertThatThrownBy(() -> {
            json.acceptBytes(("{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\",\"message\":\"" + LEAK + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
            json.finish();
        })
            .isInstanceOfSatisfying(RelayErrorException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(402);
                assertThat(error.getMessage()).isEqualTo("Source error 402 (insufficient_quota)");
            });

        ChatCompletionAccumulator stream = new ChatCompletionAccumulator(JSON, error -> mapper.fromInBand(error, "account 3", null));
        assertThatThrownBy(() -> {
            stream.acceptBytes(("data: {\"error\":{\"message\":\"maximum context length is 8192\",\"type\":\"invalid_request_error\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
            stream.finish();
        }).isInstanceOfSatisfying(RelayErrorException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(400);
            assertThat(error.isContextExceeded()).isTrue();
        });
        assertThat(logged.list).hasSize(2);
    }
}
