package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierStatusService;

/**
 * The request log keeps the kind of a relay's failure under the name of {@link UpstreamFailureClassifier.RelayFailure},
 * and the status page counts the attempts by the same four names, which it can only spell out (it is in another package
 * than the classifier). A new kind, or another name for one, has to reach the status page too.
 */
class RelayFailureLogNamesTest {

    @Test
    void theStatusPageKnowsEveryKindOfRelayFailureByTheNameTheLogHolds() {
        assertThat(Arrays.stream(UpstreamFailureClassifier.RelayFailure.values()).map(Enum::name).toList())
            .containsExactlyInAnyOrder(
                SourceTierStatusService.INTERRUPTED,
                SourceTierStatusService.CONVERSION_REJECTED,
                SourceTierStatusService.CONTEXT_EXCEEDED,
                SourceTierStatusService.MODEL_NOT_SERVED);
    }
}
