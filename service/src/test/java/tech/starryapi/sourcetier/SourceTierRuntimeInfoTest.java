package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.Pair;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;

class SourceTierRuntimeInfoTest {

    @Test
    void thisReleaseRunsNothingOfTheFeatureYet() {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo();

        assertThat(runtime.nativeTiers()).isFalse();
        assertThat(runtime.unifyEndpoints()).isFalse();
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
