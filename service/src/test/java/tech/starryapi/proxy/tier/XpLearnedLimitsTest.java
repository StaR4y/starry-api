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

    private static MemberModel mapping(String accountId, String modelId, String wireModel, int window, String verifiedHash) {
        return new MemberModel("anthropic", accountId, modelId, wireModel, window, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), verifiedHash);
    }
}
