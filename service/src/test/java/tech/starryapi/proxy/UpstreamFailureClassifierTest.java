package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.EgressFailure;
import tech.starryapi.platform.upstream.UpstreamException;

class UpstreamFailureClassifierTest {

    @Test
    void recoverableServerAndAnthropicConcurrencyFailuresEnterTheCircuitWindow() {
        assertCircuitFailure(new UpstreamException(503, "unavailable", true), 503);
        assertCircuitFailure(new UpstreamException(408, "timeout", true), 408);
        assertCircuitFailure(new UpstreamException(409, "concurrent request", true), 409);
    }

    @Test
    void networkTransportFailuresAreRetryableCircuitFailures() {
        assertCircuitFailure(new ConnectException("connection refused"), null);
        assertCircuitFailure(new RuntimeException(new UnknownHostException("upstream.invalid")), null);
    }

    @Test
    void tlsWrappedTimeoutsAndHandshakeTimeoutsRemainRecoverable() {
        assertCircuitFailure(
            new SSLException("TLS transport wrapper", new SocketTimeoutException("read timed out")),
            null
        );
        assertCircuitFailure(new SslHandshakeTimeoutException("handshake timed out"), null);
        assertCircuitFailure(new SSLException("connection reset by peer"), null);
    }

    @Test
    void nonRecoverableParserResponsesAndTlsConfigurationErrorsStayOutOfTheCircuitWindow() {
        UpstreamFailureClassifier.Classification parser = UpstreamFailureClassifier.classify(
            new UpstreamException(502, "invalid JSON", false)
        );
        UpstreamFailureClassifier.Classification tls = UpstreamFailureClassifier.classify(
            new SSLException("certificate rejected")
        );

        assertThat(parser.type()).isEqualTo(AccountPoolService.UpstreamErrorType.FATAL);
        assertThat(parser.retryable()).isFalse();
        assertThat(parser.circuitFailure()).isFalse();
        assertThat(tls.retryable()).isFalse();
        assertThat(tls.circuitFailure()).isFalse();
    }

    @Test
    void accountRateLimitsRemainRetryableWithoutOpeningTheModelFamilyCircuit() {
        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(
            new UpstreamException(429, "rate limited", true)
        );

        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.RECOVERABLE);
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.circuitFailure()).isFalse();
    }

    @Test
    void requestErrorsNeverSpreadAcrossTheAccountPool() {
        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(
            new UpstreamException(400, "invalid request", false)
        );

        assertThat(classification.scope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(classification.retryable()).isFalse();
        assertThat(classification.crossAccountRetryAllowed()).isFalse();
    }

    @Test
    void platformSpecificRateLimitEvidenceKeepsModelScope() {
        ProxyAccountEntity openAi = new ProxyAccountEntity();
        openAi.setProvider("OpenAI");
        openAi.setAuthMethod("api-key");

        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(
            new UpstreamException(429, "team_model rate limit reached", true),
            openAi
        );

        assertThat(classification.scope()).isEqualTo(AccountFailureScope.CAPACITY_MODEL);
        assertThat(classification.retryable()).isTrue();
    }

    @Test
    void unknownAnthropicForbiddenIsQuarantinedInsteadOfPermanentlyDisabled() {
        ProxyAccountEntity anthropic = new ProxyAccountEntity();
        anthropic.setProvider("Anthropic");
        anthropic.setAuthMethod("api-key");

        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(
            new UpstreamException(403, "permission denied", false),
            anthropic
        );

        assertThat(classification.scope()).isEqualTo(AccountFailureScope.UNKNOWN);
        assertThat(classification.cooldown()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(classification.permanentCredentialFailure()).isFalse();
    }

    @Test
    void structuredRevocationIsTheOnlyImmediatePermanentCredentialFailure() {
        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(
            new UpstreamException(401, "token_revoked", false)
        );

        assertThat(classification.scope()).isEqualTo(AccountFailureScope.CREDENTIAL);
        assertThat(classification.permanentCredentialFailure()).isTrue();
        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.FATAL);
    }

    @Test
    void aResponseTimeoutAfterTheRequestWasSentIsNotAnAccountFailureAndIsNeverRetriedElsewhere() {
        UpstreamFailureClassifier.Classification timeout = UpstreamFailureClassifier.classify(
            new RuntimeException("request failed", new io.netty.handler.timeout.ReadTimeoutException())
        );

        assertThat(timeout.retryable()).isFalse();
        assertThat(timeout.crossAccountRetryAllowed()).isFalse();
        assertThat(timeout.circuitFailure()).isFalse();
        assertThat(timeout.scope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(UpstreamFailureClassifier.isResponseTimeoutAfterDispatch(
            new RuntimeException(new io.netty.handler.timeout.ReadTimeoutException())
        )).isTrue();
        // A failed connect is different: nothing was sent, so another account may try.
        assertCircuitFailure(new ConnectException("connection refused"), null);
        assertThat(UpstreamFailureClassifier.neverReachedUpstream(new RuntimeException(new ConnectException("refused")))).isTrue();
        assertThat(UpstreamFailureClassifier.neverReachedUpstream(new RuntimeException("reset by peer"))).isFalse();
    }

    @Test
    void requestShapedClientErrorsAndModelAccessForbiddenDoNotQuarantineTheAccount() {
        for (int status : new int[] {405, 411, 413, 414, 415, 431}) {
            UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(
                new UpstreamException(status, "client error", false)
            );
            assertThat(classification.scope()).as("status %s", status).isEqualTo(AccountFailureScope.REQUEST);
            assertThat(classification.retryable()).isFalse();
        }
        UpstreamFailureClassifier.Classification modelAccess = UpstreamFailureClassifier.classify(
            new UpstreamException(403, "The model `gpt-9` does not exist or you do not have access to it (model_not_found)", false)
        );
        assertThat(modelAccess.scope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(modelAccess.cooldown()).isEqualTo(java.time.Duration.ZERO);
    }

    @Test
    void providerWideOverloadCoolsDownBrieflyInsteadOfTenMinutes() {
        UpstreamFailureClassifier.Classification overloaded = UpstreamFailureClassifier.classify(
            new UpstreamException(529, "overloaded_error", true)
        );

        assertThat(overloaded.scope()).isEqualTo(AccountFailureScope.PROVIDER_POOL);
        assertThat(overloaded.cooldown()).isEqualTo(java.time.Duration.ofMinutes(1));
        assertThat(overloaded.retryable()).isTrue();
    }

    @Test
    void aRouteFailureBeforeAnythingWasSentMovesToAnotherAccountWithoutBlamingThisOne() {
        Throwable error = new RuntimeException("request failed", EgressException.notSent(
            EgressFailure.PROXY_CONNECT_FAILED, new ConnectException("refused 127.0.0.1:20001")));

        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(error);

        assertThat(classification.retryable()).isTrue();
        assertThat(classification.crossAccountRetryAllowed()).isTrue();
        assertThat(classification.circuitFailure()).isFalse();
        assertThat(classification.scope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(classification.cooldown()).isEqualTo(java.time.Duration.ZERO);
        assertThat(classification.permanentCredentialFailure()).isFalse();
        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.RECOVERABLE);
        // The failure kept for logs and messages is the fixed one, not the transport text under it.
        assertThat(classification.failure()).isInstanceOf(EgressException.class);
        assertThat(classification.failure().getMessage()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED.message());
        assertThat(UpstreamFailureClassifier.neverReachedUpstream(error)).isTrue();
    }

    @Test
    void aRouteFailureAfterTheRequestMayHaveBeenSentIsNeitherRetriedNorAccountedAndStaysBillingUncertain() {
        for (Throwable cause : new Throwable[] {new io.netty.handler.timeout.ReadTimeoutException(),
            new java.net.SocketException("Connection reset"), new ConnectException("late connect error")}) {
            Throwable error = EgressException.maybeSent(EgressFailure.PROXY_TRANSPORT_FAILED, cause);

            UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(error);

            assertThat(classification.retryable()).as(cause.toString()).isFalse();
            assertThat(classification.crossAccountRetryAllowed()).isFalse();
            assertThat(classification.circuitFailure()).isFalse();
            assertThat(classification.scope()).isEqualTo(AccountFailureScope.REQUEST);
            // What the route knows wins over what its transport happened to report underneath.
            assertThat(UpstreamFailureClassifier.neverReachedUpstream(error)).as(cause.toString()).isFalse();
        }
        // ...and a route failure before sending stays unbilled whatever the transport reported.
        assertThat(UpstreamFailureClassifier.neverReachedUpstream(
            EgressException.notSent(EgressFailure.NODE_NOT_ACTIVE, new io.netty.handler.timeout.ReadTimeoutException()))).isTrue();
    }

    @Test
    void theEgressRootStopsBeforeItsTransportCause() {
        Throwable error = new RuntimeException(EgressException.maybeSent(EgressFailure.PROXY_TRANSPORT_FAILED,
            new RuntimeException("proxy user:secret@10.0.0.1 rejected")));
        assertThat(EgressException.rootCause(error)).isInstanceOf(EgressException.class);
        assertThat(EgressException.rootCause(new IllegalStateException("x", new IllegalArgumentException("y"))))
            .hasMessage("y");
        assertThat(EgressException.rootCause(null)).isNull();
    }

    private void assertCircuitFailure(Throwable error, Integer statusCode) {
        UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(error);

        assertThat(classification.statusCode()).isEqualTo(statusCode);
        assertThat(classification.type()).isEqualTo(AccountPoolService.UpstreamErrorType.RECOVERABLE);
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.circuitFailure()).isTrue();
    }

    private static final class SslHandshakeTimeoutException extends SSLException {

        private SslHandshakeTimeoutException(String reason) {
            super(reason);
        }
    }
}
