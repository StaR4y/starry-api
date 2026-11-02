package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.Pair;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;

class SourceTierRuntimeInfoTest {

    @Test
    void thisReleaseRunsTheNativeTiersTheSelectorsTheAllEndpointsSwitchTheRelayRowsAndTheChatMessagesResponsesAndGeminiPairsOnTrial() {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo();

        // Native tiering, the selectors and the all-endpoints switch are complete: requests are
        // planned over the tiers, a client can name the one it wants, and a Claude model asked through Chat Completions or
        // Responses can be served on the Anthropic route.
        assertThat(runtime.nativeTiers()).isTrue();
        assertThat(runtime.selectors()).isTrue();
        assertThat(runtime.unifyEndpoints()).isTrue();
        // A relay can be registered: the pools of untiered traffic leave it out from then on.
        assertThat(runtime.xpMembers()).isTrue();
        // The Chat, Messages and Responses pairs of the anthropic and openai vendors, the Gemini entry for the
        // anthropic and google vendors and the Chat entry for google are offered on trial: their gates open to listed
        // keys only. Everything else the cross-protocol phases bring is still off.
        List<String> offered = List.of(
            "anthropic/CHAT", "openai/CHAT", "google/CHAT", "anthropic/MESSAGES", "openai/MESSAGES",
            "anthropic/RESPONSES", "openai/RESPONSES", "anthropic/GEMINI", "google/GEMINI");
        assertThat(runtime.pairs()).isNotEmpty().allSatisfy(pair ->
            assertThat(runtime.pairStatus(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint()))
                .as(pair.toString())
                .isEqualTo(offered.contains(pair.vendor() + "/" + pair.entryEndpoint()) ? PairStatus.EXPERIMENTAL : PairStatus.NOT_OFFERED));
        assertThat(runtime.pairs().stream().filter(pair -> offered.contains(pair.vendor() + "/" + pair.entryEndpoint())).count())
            .isEqualTo(offered.size());
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
