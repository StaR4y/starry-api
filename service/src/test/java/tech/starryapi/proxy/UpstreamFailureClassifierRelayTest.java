package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.EgressFailure;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.proxy.UpstreamFailureClassifier.Classification;
import tech.starryapi.proxy.UpstreamFailureClassifier.RelayFailure;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tools.jackson.databind.ObjectMapper;

/**
 * How the failure of an attempt at an OpenAI-format relay is classified (design 7.8): the interruption that is never
 * retried, the model the relay does not serve, the prompt or converted request it refuses, and the failures that keep
 * the rules every other upstream has. The relay error mapper makes the exceptions, as it does in production.
 */
class UpstreamFailureClassifierRelayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RelayErrorMapper MAPPER = new RelayErrorMapper(JSON);

    private final ProxyAccountEntity relay = relayAccount();
    private final TierDispatch dispatch = relayDispatch();

    // --- the interruption ---

    @Test
    void anInterruptionIsATransportFailureThatIsNeverRetriedAnywhere() {
        RelayAbortedAfterDispatchException aborted = new RelayAbortedAfterDispatchException("The exchange broke", new ReadTimeoutException());

        Classification classification = UpstreamFailureClassifier.classify(aborted, relay, dispatch);

        assertThat(classification.relayFailure()).isEqualTo(RelayFailure.INTERRUPTED);
        assertThat(classification.interrupted()).isTrue();
        assertThat(classification.crossTierOnly()).isFalse();
        assertThat(classification.scope()).isEqualTo(AccountFailureScope.TRANSPORT);
        assertThat(classification.cooldown()).isEqualTo(Duration.ofMinutes(1));
        assertThat(classification.statusCode()).isEqualTo(502);
        assertThat(classification.retryable()).isFalse();
        assertThat(classification.crossAccountRetryAllowed()).isFalse();
        assertThat(classification.circuitFailure()).isFalse();
        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.FATAL);
        assertThat(classification.permanentCredentialFailure()).isFalse();
        // The words of the exception are the ones that are shown, not the transport error under it.
        assertThat(classification.failure()).isSameAs(aborted);
    }

    @Test
    void anInterruptionIsOneWhereverItIsFoundAndWhateverItsCauseIs() {
        RelayAbortedAfterDispatchException plain = new RelayAbortedAfterDispatchException("The exchange broke");
        RelayAbortedAfterDispatchException afterTheEgress = new RelayAbortedAfterDispatchException("The exchange broke",
            EgressException.maybeSent(EgressFailure.PROXY_TRANSPORT_FAILED, new IOException("reset")));
        RuntimeException wrapped = new RuntimeException("wrapped", plain);

        for (Throwable error : List.of(plain, afterTheEgress, wrapped)) {
            Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);
            assertThat(classification.interrupted()).as(error.toString()).isTrue();
            assertThat(classification.retryable()).isFalse();
            assertThat(classification.scope()).isEqualTo(AccountFailureScope.TRANSPORT);
        }
    }

    @Test
    void theSameInterruptionOfANativeAttemptIsClassifiedAsEverFailureOfThatStatusIs() {
        RelayAbortedAfterDispatchException aborted = new RelayAbortedAfterDispatchException("The exchange broke");

        Classification untiered = UpstreamFailureClassifier.classify(aborted, relay);

        assertThat(untiered.relayFailure()).isNull();
        assertThat(untiered.retryable()).isTrue();
        assertThat(UpstreamFailureClassifier.classify(aborted, relay, TierDispatch.nativeDispatch())).isEqualTo(untiered);
        assertThat(UpstreamFailureClassifier.classify(aborted, relay, null)).isEqualTo(untiered);
    }

    // --- a model the relay does not serve ---

    @Test
    void aModelThatIsNotServedIsTheModelsProblemOnThatAccountAndTheRequestGoesOn() {
        for (String body : List.of(
            "{\"error\":{\"message\":\"The model `claude-x` does not exist\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}",
            "{\"error\":{\"message\":\"No available channel for model claude-x under group default\",\"type\":\"new_api_error\"}}")) {
            for (int httpStatus : List.of(400, 403, 404, 422, 503)) {
                UpstreamException error = MAPPER.fromResponse(httpStatus, body, null, "relay-1", null);
                // Whatever status the relay chose, the mapper makes it the 404 that the classification looks for.
                assertThat(error.getStatusCode()).isEqualTo(404);
                assertModelNotServed(UpstreamFailureClassifier.classify(error, relay, dispatch), error);
            }
        }
    }

    @Test
    void aNumberedStatusWithTheModelsNameInItsWordsIsRecognisedToo() {
        for (int status : List.of(400, 403, 404, 422, 503)) {
            UpstreamException error = new UpstreamException(status, "Source error " + status + " (model_not_found)", status == 503);
            assertModelNotServed(UpstreamFailureClassifier.classify(error, relay, dispatch), error);
        }
        UpstreamException forbidden = new UpstreamException(403, "Source error 403 (unsupported_model)", false);
        assertModelNotServed(UpstreamFailureClassifier.classify(forbidden, relay, dispatch), forbidden);
    }

    private void assertModelNotServed(Classification classification, UpstreamException error) {
        assertThat(classification.relayFailure()).isEqualTo(RelayFailure.MODEL_NOT_SERVED);
        assertThat(classification.scope()).isEqualTo(AccountFailureScope.CAPACITY_MODEL);
        assertThat(classification.cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.crossAccountRetryAllowed()).isTrue();
        assertThat(classification.crossTierOnly()).isFalse();
        assertThat(classification.interrupted()).isFalse();
        assertThat(classification.circuitFailure()).isFalse();
        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.RECOVERABLE);
        assertThat(classification.statusCode()).isEqualTo(error.getStatusCode());
        assertThat(classification.failure()).isSameAs(error);
    }

    @Test
    void theModelCooldownIsKeyedByTheMappingNotByTheNameTheClientUsed() {
        // What the facade hands to admission and to the failure's evidence for a relay attempt (design 4.4, 7.5).
        assertThat(dispatch.modelScope()).isEqualTo("claude-sonnet-4.5");
        assertThat(dispatch.wireModel()).isEqualTo("Claude-Sonnet-4.5-Relay");
        assertThat(dispatch.endpointScope()).isEqualTo("xp:/chat/completions");
        assertThat(TierDispatch.nativeDispatch().modelScope()).isNull();
        assertThat(TierDispatch.nativeDispatch().endpointScope()).isNull();
    }

    @Test
    void aStatusThatDoesNotSayItIsTheModelStaysWithTheRulesOfThatStatus() {
        UpstreamException overloaded = new UpstreamException(503, "Source error 503 (overloaded_error)", true);
        UpstreamException limited = new UpstreamException(429, "Source error 429 (rate_limit_exceeded)", true);

        assertThat(UpstreamFailureClassifier.classify(overloaded, relay, dispatch))
            .isEqualTo(UpstreamFailureClassifier.classify(overloaded, relay));
        assertThat(UpstreamFailureClassifier.classify(limited, relay, dispatch))
            .isEqualTo(UpstreamFailureClassifier.classify(limited, relay));
    }

    // --- a request the relay refuses ---

    @Test
    void aPromptThatDoesNotFitIsTheRequestsProblemWithThisSourceAndMayCrossToANativeTier() {
        UpstreamException error = MAPPER.fromResponse(400,
            "{\"error\":{\"message\":\"This model's maximum context length is 200000 tokens\",\"code\":\"context_length_exceeded\"}}",
            null, "relay-1", null);

        Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);

        assertThat(classification.relayFailure()).isEqualTo(RelayFailure.CONTEXT_EXCEEDED);
        assertThat(classification.crossTierOnly()).isTrue();
        assertRequestRefusal(classification, 400);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 422})
    void aConvertedRequestThatIsRefusedMayCrossToANativeTierButNotToAnotherRelay(int status) {
        UpstreamException error = MAPPER.fromResponse(status, "{\"error\":{\"message\":\"messages.1: unexpected field\",\"type\":\"invalid_request_error\"}}",
            null, "relay-1", null);

        Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);

        assertThat(classification.relayFailure()).isEqualTo(RelayFailure.CONVERSION_REJECTED);
        assertThat(classification.crossTierOnly()).isTrue();
        assertRequestRefusal(classification, error.getStatusCode());
    }

    private static void assertRequestRefusal(Classification classification, int status) {
        assertThat(classification.statusCode()).isEqualTo(status);
        assertThat(classification.scope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(classification.cooldown()).isEqualTo(Duration.ZERO);
        assertThat(classification.retryable()).isFalse();
        assertThat(classification.crossAccountRetryAllowed()).isFalse();
        assertThat(classification.circuitFailure()).isFalse();
        assertThat(classification.interrupted()).isFalse();
        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.FATAL);
    }

    @Test
    void aRefusalOfANativeAttemptIsNotACrossingToAnything() {
        UpstreamException error = MAPPER.fromResponse(400, "{\"error\":{\"message\":\"bad\",\"type\":\"invalid_request_error\"}}", null, "relay-1", null);

        Classification classification = UpstreamFailureClassifier.classify(error, relay, TierDispatch.nativeDispatch());

        assertThat(classification.crossTierOnly()).isFalse();
        assertThat(classification).isEqualTo(UpstreamFailureClassifier.classify(error, relay));
    }

    // --- what keeps the rules every upstream has ---

    @Test
    void aFailureThatHasNoRuleOfItsOwnIsJudgedByItsStatusAsAlways() {
        List<UpstreamException> errors = List.of(
            new UpstreamException(408, "Source error 408", false),
            new UpstreamException(409, "Source error 409", false),
            new UpstreamException(429, "Source error 429 (rate_limit_exceeded)", true),
            new UpstreamException(429, "Source error 429 (model_limit)", true),
            new UpstreamException(500, "Source error 500", true),
            new UpstreamException(501, "Source error 501", true),
            new UpstreamException(502, "The source returned no answer", true),
            new UpstreamException(503, "Source error 503 (overloaded_error)", true),
            new UpstreamException(504, "Source error 504", true),
            new UpstreamException(529, "Source error 529", true),
            new UpstreamException(405, "Source error 405", false));

        for (UpstreamException error : errors) {
            Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);
            assertThat(classification).as(error.getMessage()).isEqualTo(UpstreamFailureClassifier.classify(error, relay));
            assertThat(classification.relayFailure()).isNull();
        }
    }

    // --- the relay turns its own key down (D5) ---

    @Test
    void aRelayThatRejectsItsOwnKeyHasFailedAsThatAccountAndTheRequestGoesOnToAnotherAccountOrTier() {
        List<UpstreamException> errors = List.of(
            new UpstreamException(401, "Source error 401 (invalid_api_key)", false),
            new UpstreamException(401, "Source error 401 (token_revoked)", false),
            new UpstreamException(403, "Source error 403 (permission_denied)", false),
            new UpstreamException(403, "Source error 403 (account_disabled)", false),
            new UpstreamException(401, "Source error 401", false),
            // What the mapper makes of the three ways a relay says it: a status, a name in the body, and an error inside a 200.
            MAPPER.fromResponse(401, "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}",
                null, "relay-1", "sk-test"),
            MAPPER.fromResponse(403, "<html>Forbidden</html>", null, "relay-1", "sk-test"),
            MAPPER.fromInBand(JSON.readTree("{\"message\":\"invalid x-api-key\",\"type\":\"authentication_error\"}"), "relay-1", "sk-test"),
            MAPPER.fromInBand(JSON.readTree("{\"message\":\"denied\",\"type\":\"permission_error\"}"), "relay-1", "sk-test"));

        for (UpstreamException error : errors) {
            Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);

            assertThat(error.getStatusCode()).as(error.getMessage()).isIn(401, 403);
            assertThat(classification.relayFailure()).as(error.getMessage()).isEqualTo(RelayFailure.KEY_REFUSED);
            assertThat(classification.keyRefused()).isTrue();
            assertThat(classification.statusCode()).isEqualTo(error.getStatusCode());
            // The credentials of the account are what failed: it rests as a native account does for bad ones.
            assertThat(classification.scope()).isEqualTo(AccountFailureScope.CREDENTIAL);
            assertThat(classification.cooldown()).isEqualTo(Duration.ofMinutes(10));
            // A rejection is given before anything is generated, so the request goes on to another account and to another tier.
            assertThat(classification.retryable()).isTrue();
            assertThat(classification.crossAccountRetryAllowed()).isTrue();
            assertThat(classification.crossTierOnly()).isFalse();
            assertThat(classification.interrupted()).isFalse();
            assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.RECOVERABLE);
            // A relay's word does not disable the administrator's account, and the old circuit breaker never counts a relay.
            assertThat(classification.permanentCredentialFailure()).isFalse();
            assertThat(classification.circuitFailure()).isFalse();
            assertThat(classification.failure()).isSameAs(error);
        }
    }

    @Test
    void theSameRejectionOfANativeAttemptKeepsTheRulesItAlwaysHad() {
        UpstreamException unauthorized = new UpstreamException(401, "Source error 401 (invalid_api_key)", false);
        UpstreamException forbidden = new UpstreamException(403, "Source error 403 (permission_denied)", false);
        UpstreamException revoked = new UpstreamException(401, "token_revoked", false);

        for (UpstreamException error : List.of(unauthorized, forbidden, revoked)) {
            Classification untiered = UpstreamFailureClassifier.classify(error, relay);

            assertThat(untiered.relayFailure()).isNull();
            assertThat(untiered.keyRefused()).isFalse();
            assertThat(UpstreamFailureClassifier.classify(error, relay, TierDispatch.nativeDispatch())).isEqualTo(untiered);
            assertThat(UpstreamFailureClassifier.classify(error, relay, null)).isEqualTo(untiered);
        }
        // A native 401 is retried on no other account, and one that says the token is gone disables the account.
        assertThat(UpstreamFailureClassifier.classify(unauthorized, relay).crossAccountRetryAllowed()).isFalse();
        assertThat(UpstreamFailureClassifier.classify(revoked, relay).permanentCredentialFailure()).isTrue();
    }

    @Test
    void aRelayThatSaysTheModelIsNotServedWithA403IsStillTheModelsProblemAndNotTheKeys() {
        UpstreamException error = MAPPER.fromResponse(403,
            "{\"error\":{\"message\":\"You do not have access to the model `claude-x`\",\"type\":\"permission_error\"}}",
            null, "relay-1", null);

        Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);

        assertThat(classification.relayFailure()).isEqualTo(RelayFailure.MODEL_NOT_SERVED);
        assertThat(classification.keyRefused()).isFalse();
    }

    @Test
    void aRelaysOutOfMoneyIsTheCapacityFailureItAlwaysWas() {
        UpstreamException error = MAPPER.fromResponse(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}",
            null, "relay-1", null);
        assertThat(error.getStatusCode()).isEqualTo(402);

        Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);

        assertThat(classification.statusCode()).isEqualTo(402);
        assertThat(classification.scope()).isEqualTo(AccountFailureScope.CAPACITY_ACCOUNT);
        assertThat(classification.cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.crossAccountRetryAllowed()).isTrue();
        assertThat(classification.relayFailure()).isNull();
        assertThat(classification).isEqualTo(UpstreamFailureClassifier.classify(error, relay));
    }

    @Test
    void aFailureThatProvesTheRequestNeverLeftIsTheTransportsToClassify() {
        ConnectException refused = new ConnectException("connection refused");
        EgressException noRoute = EgressException.notSent(EgressFailure.PROXY_CONNECT_FAILED, new IOException("no route"));

        for (Throwable error : List.of(refused, new RuntimeException("request failed", refused), noRoute)) {
            Classification classification = UpstreamFailureClassifier.classify(error, relay, dispatch);
            assertThat(classification).as(error.toString()).isEqualTo(UpstreamFailureClassifier.classify(error, relay));
            assertThat(classification.retryable()).isTrue();
            assertThat(classification.relayFailure()).isNull();
        }
    }

    @Test
    void anExceptionThatIsNoUpstreamFailureAtAllIsLeftToTheOrdinaryRules() {
        for (Throwable error : List.of(new IllegalStateException("bug"), new java.util.concurrent.TimeoutException("slow"))) {
            assertThat(UpstreamFailureClassifier.classify(error, relay, dispatch))
                .isEqualTo(UpstreamFailureClassifier.classify(error, relay));
        }
    }

    @Test
    void theNineArgumentClassificationOfTheOtherUpstreamsHasNoRelayFailure() {
        Classification classification = new Classification(new RuntimeException("x"), 500,
            AccountPoolService.UpstreamErrorType.RECOVERABLE, true, true, true, AccountFailureScope.PROVIDER_POOL,
            Duration.ofMinutes(1), false);

        assertThat(classification.relayFailure()).isNull();
        assertThat(classification.interrupted()).isFalse();
        assertThat(classification.crossTierOnly()).isFalse();
    }

    // --- fixtures ---

    private static ProxyAccountEntity relayAccount() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("relay-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl("https://relay.example.invalid/v1");
        account.setAccessToken("sk-test");
        return account;
    }

    static TierDispatch relayDispatch() {
        Member member = new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false, false, "NONE",
            540, true, true);
        return TierDispatch.openAiChat(
            new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic"),
            "claude-sonnet-4.5",
            "Claude-Sonnet-4.5-Relay",
            member,
            JSON.createObjectNode().put("stream", false),
            List.of());
    }
}
