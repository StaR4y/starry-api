package tech.starryapi.platform.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpstreamExceptionTest {

    @Test
    void generationStartedDefaultsToFalseForEveryExistingConstructor() {
        UpstreamException three = new UpstreamException(502, "boom", true);
        UpstreamException four = new UpstreamException(429, "slow", true, "7");

        assertThat(three.isGenerationStarted()).isFalse();
        assertThat(three.getStatusCode()).isEqualTo(502);
        assertThat(three.isRecoverable()).isTrue();
        assertThat(four.isGenerationStarted()).isFalse();
        assertThat(UpstreamException.retryAfterSeconds(four)).isEqualTo(7);
    }

    @Test
    void generationStartedIsCarriedByTheFiveArgumentConstructorAlongsideRetryAfter() {
        UpstreamException started = new UpstreamException(503, "cut", true, "9", true);
        UpstreamException notStarted = new UpstreamException(503, "cut", true, null, false);

        assertThat(started.isGenerationStarted()).isTrue();
        assertThat(started.getStatusCode()).isEqualTo(503);
        assertThat(started.isRecoverable()).isTrue();
        assertThat(UpstreamException.retryAfterSeconds(started)).isEqualTo(9);
        assertThat(notStarted.isGenerationStarted()).isFalse();
        assertThat(UpstreamException.retryAfterSeconds(notStarted)).isZero();
    }

    @Test
    void withGenerationStartedKeepsEverythingElseAndLeavesTheOriginalAlone() {
        UpstreamException original = new UpstreamException(429, "slow", true, "7");

        UpstreamException marked = original.withGenerationStarted();

        assertThat(marked).isNotSameAs(original);
        assertThat(marked.isGenerationStarted()).isTrue();
        assertThat(marked.getStatusCode()).isEqualTo(429);
        assertThat(marked.isRecoverable()).isTrue();
        assertThat(marked.getMessage()).isEqualTo("slow");
        assertThat(UpstreamException.retryAfterSeconds(marked)).isEqualTo(7);
        assertThat(marked.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(original.isGenerationStarted()).isFalse();
        assertThat(marked.withGenerationStarted()).isSameAs(marked);
    }
}
