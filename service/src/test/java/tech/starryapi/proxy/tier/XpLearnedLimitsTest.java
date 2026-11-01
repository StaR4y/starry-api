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

        assertThat(XpLearnedLimits.disabled().refusedFrom(mapping)).isEmpty();
    }

    // ---- a body that asked for more output than the plans judge a prompt beside ----

    @Test
    void aBodyThatAskedForALargeOutputAndWasRefusedKeepsOutAnyBodyThatAsksForAsMuchOfBoth() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learnWide(mapping, 183_000, 16_000);

        assertThat(limits.refusedAsMuch(mapping, 183_000, 16_000)).as("the same body").isTrue();
        assertThat(limits.refusedAsMuch(mapping, 183_500, 16_000)).as("more prompt").isTrue();
        assertThat(limits.refusedAsMuch(mapping, 183_000, 32_000)).as("more output").isTrue();
        assertThat(limits.refusedAsMuch(mapping, 190_000, 64_000)).as("more of both").isTrue();
        assertThat(limits.refusedAsMuch(mapping, 182_999, 16_000)).as("less prompt").isFalse();
        assertThat(limits.refusedAsMuch(mapping, 183_000, 15_999)).as("less output").isFalse();
        assertThat(limits.refusedAsMuch(mapping, 250_000, 4_096)).as("the body beside 4,096 is judged by the size alone").isFalse();
        assertThat(limits.refusedFrom(mapping)).as("the size is learned apart").isEmpty();
    }

    @Test
    void aRefusalThatIsSmallerInBothSaysMoreAndOneThatIsSmallerInOneOnlyIsKeptBesideTheOther() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learnWide(mapping, 183_000, 16_000);
        limits.learnWide(mapping, 170_000, 32_000);
        assertThat(limits.refusedAsMuch(mapping, 183_000, 16_000)).isTrue();
        assertThat(limits.refusedAsMuch(mapping, 170_000, 32_000)).isTrue();
        assertThat(limits.refusedAsMuch(mapping, 180_000, 20_000)).as("not as much of the prompt of the first, not as much output as the second").isFalse();

        limits.learnWide(mapping, 180_000, 12_000);
        assertThat(limits.refusedAsMuch(mapping, 181_000, 12_000)).isTrue();
        assertThat(limits.refusedAsMuch(mapping, 183_000, 16_000)).as("still, through the smaller refusal").isTrue();
        assertThat(limits.refusedAsMuch(mapping, 170_000, 32_000)).isTrue();

        // A refusal that one known is no larger than adds nothing.
        limits.learnWide(mapping, 190_000, 40_000);
        assertThat(limits.refusedAsMuch(mapping, 179_000, 12_000)).isFalse();
    }

    @Test
    void noMoreThanEightRefusalsOfAMappingAreKept() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        // Each is larger in the prompt and smaller in the output than the one before, so none of them says what another does.
        for (int refusal = 0; refusal < 9; refusal++) {
            limits.learnWide(mapping, 100_000 + refusal * 1_000L, 20_000 - refusal * 100L);
        }

        for (int refusal = 0; refusal < 8; refusal++) {
            assertThat(limits.refusedAsMuch(mapping, 100_000 + refusal * 1_000L, 20_000 - refusal * 100L)).isTrue();
        }
        assertThat(limits.refusedAsMuch(mapping, 108_000, 19_200)).as("the ninth is not kept").isFalse();
    }

    @Test
    void nothingIsLearnedFromAPromptOrAnOutputThatIsNone() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        limits.learnWide(mapping, 0, 16_000);
        limits.learnWide(mapping, 183_000, 0);

        assertThat(limits.refusedAsMuch(mapping, 190_000, 64_000)).isFalse();
    }

    @Test
    void whatABodyWithALargeOutputTaughtBelongsToTheMappingAsItWasConfiguredAndIsForgottenAfterSixHours() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");
        limits.learnWide(mapping, 183_000, 16_000);

        assertThat(limits.refusedAsMuch(mapping("relay-2", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1"), 190_000, 64_000)).isFalse();
        assertThat(limits.refusedAsMuch(mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 400_000, "hash-1"), 190_000, 64_000)).isFalse();
        assertThat(limits.refusedAsMuch(mapping("relay-1", "Claude-Sonnet-4.5", "relay-sonnet", 200_000, "hash-1"), 190_000, 64_000)).isTrue();

        nanos.addAndGet(Duration.ofHours(6).minusSeconds(1).toNanos());
        assertThat(limits.refusedAsMuch(mapping, 190_000, 64_000)).isTrue();
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(limits.refusedAsMuch(mapping, 190_000, 64_000)).isFalse();
        assertThat(limits.size()).isZero();
    }

    @Test
    void aNodeThatDoesNotRunTiersLearnsNoBodyWithALargeOutputEither() {
        MemberModel mapping = mapping("relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, "hash-1");

        XpLearnedLimits.disabled().learnWide(mapping, 183_000, 16_000);

        assertThat(XpLearnedLimits.disabled().refusedAsMuch(mapping, 190_000, 64_000)).isFalse();
    }

    private static MemberModel mapping(String accountId, String modelId, String wireModel, int window, String verifiedHash) {
        return new MemberModel("anthropic", accountId, modelId, wireModel, window, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), verifiedHash);
    }
}
