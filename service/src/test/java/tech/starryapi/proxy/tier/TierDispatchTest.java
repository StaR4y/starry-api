package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.proxy.tier.TierDispatch.Kind;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** What a dispatch is, and what it must not be (design 7.5): a native one carries nothing, a relay one carries everything. */
class TierDispatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PairKey PAIR = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic");
    private static final ObjectNode BODY = JSON.createObjectNode().put("stream", false);

    @Test
    void aNativeDispatchIsOneValueThatCarriesNothingOfARelay() {
        TierDispatch dispatch = TierDispatch.nativeDispatch();

        assertThat(dispatch.kind()).isEqualTo(Kind.NATIVE);
        assertThat(dispatch).isSameAs(TierDispatch.nativeDispatch());
        assertThat(dispatch.pair()).isNull();
        assertThat(dispatch.wireBody()).isNull();
        assertThat(dispatch.degraded()).isEmpty();
        assertThat(dispatch.modelScope()).isNull();
        assertThat(dispatch.endpointScope()).isNull();
    }

    @Test
    void aNativeDispatchCannotBeGivenAnythingOfARelay() {
        assertThatThrownBy(() -> new TierDispatch(Kind.NATIVE, PAIR, null, null, null, null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TierDispatch(Kind.NATIVE, null, null, "wire", null, null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TierDispatch(Kind.NATIVE, null, null, null, null, null, null, List.of("image")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRelayDispatchNamesTheMappingTheWireModelTheEndpointAndTheScopesOfItsAdmission() {
        List<String> degraded = new ArrayList<>(List.of("cache_control"));

        TierDispatch dispatch = TierDispatch.openAiChat(PAIR, "claude-sonnet-4.5", "Wire-Name", member("OPENAI_CHAT"), BODY, degraded);
        degraded.add("image");

        assertThat(dispatch.kind()).isEqualTo(Kind.OPENAI_CHAT);
        assertThat(dispatch.wireModel()).isEqualTo("Wire-Name");
        assertThat(dispatch.wireBody()).isSameAs(BODY);
        assertThat(dispatch.outgoingEndpoint()).isEqualTo("/chat/completions");
        assertThat(dispatch.modelScope()).isEqualTo("claude-sonnet-4.5");
        assertThat(dispatch.endpointScope()).isEqualTo("xp:/chat/completions");
        assertThat(dispatch.degraded()).containsExactly("cache_control");
        assertThat(TierDispatch.openAiChat(PAIR, "claude-sonnet-4.5", "Wire-Name", member("OPENAI_CHAT"), BODY, null).degraded()).isEmpty();
    }

    @Test
    void aRelayDispatchNeedsAMemberThatIsRegisteredAsOneAndEverythingItSends() {
        Member nativeMember = member(null);
        Member otherProtocol = member("OPENAI_RESPONSES");

        for (Member refused : List.of(nativeMember, otherProtocol)) {
            assertThatThrownBy(() -> TierDispatch.openAiChat(PAIR, "m", "w", refused, BODY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TierDispatch.openAiChat(null, "m", "w", member("OPENAI_CHAT"), BODY, List.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TierDispatch.openAiChat(PAIR, "m", "w", null, BODY, List.of()))
            .isInstanceOf(NullPointerException.class);
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> TierDispatch.openAiChat(PAIR, blank, "w", member("OPENAI_CHAT"), BODY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TierDispatch.openAiChat(PAIR, "m", blank, member("OPENAI_CHAT"), BODY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TierDispatch.openAiChat(PAIR, "m", "w", member("OPENAI_CHAT"), null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TierDispatch.openAiChat(PAIR, "m", "w", member("OPENAI_CHAT"), JSON.createArrayNode(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Member member(String protocol) {
        return new Member("anthropic", "relay-1", "relay", protocol, true, false, false, false, false, false, "NONE", 540, true, true);
    }
}
