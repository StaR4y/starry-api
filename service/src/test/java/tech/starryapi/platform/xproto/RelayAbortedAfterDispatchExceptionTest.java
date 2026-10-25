package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.proxy.tier.TierRetryPolicy;

class RelayAbortedAfterDispatchExceptionTest {

    @Test
    void itIsARecoverable502ThatSaysGenerationMayHaveStarted() {
        RelayAbortedAfterDispatchException error = new RelayAbortedAfterDispatchException("The relay stopped");

        assertThat(error).isInstanceOf(UpstreamException.class);
        assertThat(error.getStatusCode()).isEqualTo(502);
        assertThat(error.isRecoverable()).isTrue();
        assertThat(error.isGenerationStarted()).isTrue();
        assertThat(error.getMessage()).isEqualTo("The relay stopped");
        assertThat(error.getCause()).isNull();
        assertThat(UpstreamException.retryAfterSeconds(error)).isZero();
    }

    @Test
    void itKeepsTheFailureThatBrokeTheExchange() {
        IOException cause = new IOException("connection reset");

        RelayAbortedAfterDispatchException error = new RelayAbortedAfterDispatchException("The relay stopped", cause);

        assertThat(error.getCause()).isSameAs(cause);
        assertThat(error.isGenerationStarted()).isTrue();
    }

    @Test
    void markingItAsStartedChangesNothing() {
        RelayAbortedAfterDispatchException error = new RelayAbortedAfterDispatchException("The relay stopped");

        assertThat(error.withGenerationStarted()).isSameAs(error);
    }

    @Test
    void theTierRetryRulesSeeItAsStartedEvenWhenWrapped() {
        RelayAbortedAfterDispatchException aborted = new RelayAbortedAfterDispatchException("The relay stopped");

        assertThat(TierRetryPolicy.notStarted(aborted)).isFalse();
        assertThat(TierRetryPolicy.notStarted(new IllegalStateException("wrapped", aborted))).isFalse();
        // The same 502 without the flag is a failure that cost nothing.
        assertThat(TierRetryPolicy.notStarted(new UpstreamException(502, "The relay stopped", true))).isTrue();
    }
}
