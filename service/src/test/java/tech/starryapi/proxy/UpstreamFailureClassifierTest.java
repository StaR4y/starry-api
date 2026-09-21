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
