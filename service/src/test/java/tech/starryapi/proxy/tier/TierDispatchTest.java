package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.proxy.tier.TierDispatch.Kind;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
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
    void theOutputLimitOfARelayIsTheOneItsBodyAsksFor() {
        assertThat(relayWith(JSON.createObjectNode().put("max_tokens", 100)).forwardedOutputLimit()).isEqualTo(100);
        assertThat(relayWith(JSON.createObjectNode().put("max_completion_tokens", 300)).forwardedOutputLimit()).isEqualTo(300);
        assertThat(relayWith(JSON.createObjectNode().put("max_completion_tokens", 300).put("max_tokens", 100)).forwardedOutputLimit())
            .isEqualTo(300);
        // A body that names none, or none that makes sense, leaves the limit to the reservation.
        assertThat(relayWith(JSON.createObjectNode().put("stream", true)).forwardedOutputLimit()).isZero();
        assertThat(relayWith(JSON.createObjectNode().put("max_tokens", -5)).forwardedOutputLimit()).isZero();
        assertThat(relayWith(JSON.createObjectNode().put("max_tokens", "many")).forwardedOutputLimit()).isZero();
        assertThat(TierDispatch.nativeDispatch().forwardedOutputLimit()).isZero();
    }

    @Test
    void aRelayAsItWasGivenTheRequestBeforeTheGatewayChoseAnOutputIsTheSameBodyHeldToTheFallbackAndMeasuredAgain() {
        ObjectNode body = JSON.createObjectNode().put("max_tokens", 16_000);
        body.putArray("messages").addObject().put("role", "user").put("content", "a".repeat(4_000));
        TierDispatch dispatch = TierDispatch.openAiChat(PAIR, "claude-sonnet-4.5", "Wire-Name", member("OPENAI_CHAT"), body, List.of("verbosity"));
        MemberModel mapping = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "Wire-Name", 200_000, 8192, true, null, null);
        TierPlan.Relay relay = new TierPlan.Relay(dispatch, mapping, 1_100, 16_000);

        TierPlan.Relay before = relay.withTheOutputOfBefore().orElseThrow();

        assertThat(before.dispatch().forwardedOutputLimit()).isEqualTo(RequestBounds.FALLBACK_OUTPUT_TOKENS);
        assertThat(before.gatewayOutput()).as("the limit is no longer the gateway's").isZero();
        assertThat(before.mapping()).isSameAs(mapping);
        assertThat(before.accountId()).isEqualTo("relay-1");
        assertThat(before.dispatch().wireModel()).isEqualTo("Wire-Name");
        assertThat(before.dispatch().modelId()).isEqualTo("claude-sonnet-4.5");
        assertThat(before.dispatch().degraded()).containsExactly("verbosity");
        assertThat(before.dispatch().wireBody().path("messages")).isEqualTo(body.path("messages"));
        assertThat(before.estimatedInput()).as("measured on its own body").isEqualTo(RelayUsageNormalizer.estimateInputTokens(before.dispatch().wireBody()));
        assertThat(before.inputBound()).as("never above what the tier reserved for").isLessThanOrEqualTo(relay.inputBound());
        assertThat(relay.dispatch().forwardedOutputLimit()).as("the relay it was made from is not changed").isEqualTo(16_000);
        // Nothing to give back where the limit is the client's, or the request was given the fallback already.
        assertThat(before.withTheOutputOfBefore()).isEmpty();
        assertThat(new TierPlan.Relay(dispatch, mapping, 1_100).withTheOutputOfBefore()).isEmpty();
    }

    private static TierDispatch relayWith(ObjectNode body) {
        return TierDispatch.openAiChat(PAIR, "claude-sonnet-4.5", "Wire-Name", member("OPENAI_CHAT"), body, List.of());
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
