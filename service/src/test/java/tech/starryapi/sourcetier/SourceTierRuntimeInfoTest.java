package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.Pair;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;

class SourceTierRuntimeInfoTest {

    @Test
    void thisReleaseRunsTheNativeTiersTheSelectorsAndTheAllEndpointsSwitchAndNothingElseOfTheFeatureYet() {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo();

        // Native tiering (P2-09), the selectors (P3-01) and the all-endpoints switch (P3-03) are complete: requests are
        // planned over the tiers, a client can name the one it wants, and a Claude model asked through Chat Completions or
        // Responses can be served on the Anthropic route.
        assertThat(runtime.nativeTiers()).isTrue();
        assertThat(runtime.selectors()).isTrue();
        assertThat(runtime.unifyEndpoints()).isTrue();
        // What the later phases bring is still off, so that nothing is stored that only a later release would run.
        assertThat(runtime.xpMembers()).isFalse();
        assertThat(runtime.pairs()).isNotEmpty().allSatisfy(pair ->
            assertThat(runtime.pairStatus(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint())).isEqualTo(PairStatus.NOT_OFFERED));
    }

    @Test
    void everyPairAGateCanOpenIsListedOnce() {
        assertThat(new SourceTierRuntimeInfo().pairs()).hasSize(12).doesNotHaveDuplicates()
            .extracting(Pair::vendor).containsOnly("anthropic", "openai", "google");
        assertThat(new SourceTierRuntimeInfo().pairs()).extracting(Pair::sourceProtocol).containsOnly("OPENAI_CHAT");
        assertThat(new SourceTierRuntimeInfo().pairs()).extracting(Pair::entryEndpoint)
            .containsOnly("CHAT", "MESSAGES", "RESPONSES", "GEMINI");
    }

    @Test
    void capabilitiesAndPairStatusesAreWhatTheyWereBuiltWith() {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(true, true, true,
            pair -> pair.entryEndpoint().equals("CHAT") ? PairStatus.VERIFIED : pair.entryEndpoint().equals("MESSAGES") ? PairStatus.EXPERIMENTAL : null);

        assertThat(runtime.nativeTiers()).isTrue();
        assertThat(runtime.unifyEndpoints()).isTrue();
        assertThat(runtime.xpMembers()).isTrue();
        // The constructor without the selectors capability is the release before the selector task.
        assertThat(runtime.selectors()).isFalse();
        assertThat(new SourceTierRuntimeInfo(true, true, false, false, pair -> PairStatus.NOT_OFFERED).selectors()).isTrue();
        assertThat(runtime.pairStatus("anthropic", "OPENAI_CHAT", "CHAT")).isEqualTo(PairStatus.VERIFIED);
        assertThat(runtime.pairStatus("anthropic", "OPENAI_CHAT", "MESSAGES")).isEqualTo(PairStatus.EXPERIMENTAL);
        // A registry that has no answer is a pair that is not offered.
        assertThat(runtime.pairStatus("anthropic", "OPENAI_CHAT", "GEMINI")).isEqualTo(PairStatus.NOT_OFFERED);
    }

    @Test
    void aRegistryIsRequired() {
        assertThatThrownBy(() -> new SourceTierRuntimeInfo(false, false, false, null)).isInstanceOf(NullPointerException.class);
    }
}
