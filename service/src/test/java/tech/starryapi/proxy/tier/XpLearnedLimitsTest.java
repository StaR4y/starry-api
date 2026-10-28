package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;

/**
 * What a node learns of a relay's context window from its refusals (design 7.8): the smallest prompt it refused, for
 * the mapping as it was configured, for six hours.
 */
class XpLearnedLimitsTest {

    private final AtomicLong nanos = new AtomicLong();
    private final XpLearnedLimits limits = new XpLearnedLimits(nanos::get);

    @Test
    void aRelayThatRefusedNothingHasNoLimit() {
        assertThat(limits.refusedFrom(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1"))).isEmpty();
    }

    @Test
    void thePromptARelayRefusedIsTheSizeFromWhichItIsNotAskedAgain() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 150_000);

        assertThat(limits.refusedFrom(mapping)).hasValue(150_000);
    }

    @Test
    void theSizeOnlyGoesDownAsEveryRefusalNarrowsTheWindow() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 150_000);
        limits.learn(mapping, 170_000);
        assertThat(limits.refusedFrom(mapping)).hasValue(150_000);

        limits.learn(mapping, 130_000);
        assertThat(limits.refusedFrom(mapping)).hasValue(130_000);
    }

    @Test
    void nothingIsLearnedFromASizeThatIsNotAPrompt() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 0);
        limits.learn(mapping, -5);

        assertThat(limits.refusedFrom(mapping)).isEmpty();
    }

    @Test
    void whatOneMappingRefusedIsNothingToAnotherRelayOrAnotherModelOfTheSameRelay() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 150_000);

        assertThat(limits.refusedFrom(mapping("relay-2", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1"))).isEmpty();
        assertThat(limits.refusedFrom(mapping("relay-1", "claude-opus-4.5", "relay-opus", 200_000, "hash-1"))).isEmpty();
    }

    @Test
    void theModelOfAMappingIsComparedWithoutRegardToCase() {
        limits.learn(mapping("relay-1", "Claude-Sonnet-4.5", "relay-sonnet", 200_000, "hash-1"), 150_000);

        assertThat(limits.refusedFrom(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1"))).hasValue(150_000);
    }

    @Test
    void aMappingThatWasChangedSinceBeginsWithNothing() {
        limits.learn(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1"), 150_000);

        // The administrator gave the relay a larger window, another name for the model, or had it verified again.
        assertThat(limits.refusedFrom(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 400_000, "hash-1"))).isEmpty();
        assertThat(limits.refusedFrom(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet-2", 200_000, "hash-1"))).isEmpty();
        assertThat(limits.refusedFrom(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-2"))).isEmpty();
    }

    @Test
    void whatWasLearnedIsKeptForSixHoursAndThenForgotten() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");
        limits.learn(mapping, 150_000);

        nanos.addAndGet(Duration.ofHours(6).minusSeconds(1).toNanos());
        assertThat(limits.refusedFrom(mapping)).hasValue(150_000);

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(limits.refusedFrom(mapping)).isEmpty();
        assertThat(limits.size()).isZero();
    }

    @Test
    void aNodeThatDoesNotRunTiersLearnsNothing() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        XpLearnedLimits.disabled().learn(mapping, 150_000);
        XpLearnedLimits.disabled().learn(mapping, 150_000, 64_000);

        assertThat(XpLearnedLimits.disabled().refusedFrom(mapping)).isEmpty();
        assertThat(XpLearnedLimits.disabled().refusedFromWithGatewayOutput(mapping)).isEmpty();
    }

    @Test
    void aRefusalOfARequestWhoseOutputTheGatewayChoseTeachesRequestsLikeItAndNoOther() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        // A prompt of 146,000 and the model's maximum of 64,000 together did not fit: nothing is known of 146,000 alone.
        limits.learn(mapping, 146_000, 64_000);

        assertThat(limits.refusedFrom(mapping)).as("the prompt alone was never shown to be too long").isEmpty();
        assertThat(limits.refusedFromWithGatewayOutput(mapping)).hasValue(210_000);
    }

    @Test
    void whatARelayRefusedWithAnOutputTheClientWroteStillHoldsForEveryRequestAndWhatItRefusedWithTheGatewaysIsKeptApart() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 150_000);
        limits.learn(mapping, 130_000, 64_000);

        assertThat(limits.refusedFrom(mapping)).hasValue(150_000);
        assertThat(limits.refusedFromWithGatewayOutput(mapping)).hasValue(194_000);
    }

    @Test
    void theSizeOfTheGatewaysOutputKindOnlyGoesDownToo() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 150_000, 64_000);
        limits.learn(mapping, 160_000, 64_000);
        assertThat(limits.refusedFromWithGatewayOutput(mapping)).hasValue(214_000);

        limits.learn(mapping, 120_000, 64_000);
        assertThat(limits.refusedFromWithGatewayOutput(mapping)).hasValue(184_000);
    }

    @Test
    void anOutputOfNoSizeIsTheOrdinaryKind() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learn(mapping, 150_000, 0);
        limits.learn(mapping, 140_000, -5);

        assertThat(limits.refusedFrom(mapping)).hasValue(140_000);
        assertThat(limits.refusedFromWithGatewayOutput(mapping)).isEmpty();
    }

    private static MemberModel mapping(String accountId, String modelId, String wireModel, int window, String verifiedHash) {
        return new MemberModel("anthropic", accountId, modelId, wireModel, window, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), verifiedHash);
    }
}
