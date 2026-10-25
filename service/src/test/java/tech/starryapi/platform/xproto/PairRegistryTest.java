package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;

class PairRegistryTest {

    private static final PairKey CHAT_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic");
    private static final PairKey MESSAGES_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic");

    @Test
    void thisReleaseOffersNoPair() {
        // P5-05 lists the first pairs and replaces this test with the completeness of their fixtures.
        PairRegistry defaults = PairRegistry.defaults();

        assertThat(defaults.offeredPairs()).isEmpty();
        assertThat(PairRegistry.allPairs()).isNotEmpty().allSatisfy(pair -> {
            assertThat(defaults.status(pair)).isEqualTo(Status.NOT_OFFERED);
            assertThat(defaults.offered(pair)).isFalse();
        });
    }

    @Test
    void aPairThatIsNotListedIsNotOffered() {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL)));

        assertThat(registry.status(CHAT_ANTHROPIC)).isEqualTo(Status.EXPERIMENTAL);
        assertThat(registry.status(MESSAGES_ANTHROPIC)).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.status(new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai"))).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.offeredPairs()).containsExactly(CHAT_ANTHROPIC);
    }

    @Test
    void offeredPairsComeInTheOrderOfKindEntryAndVendor() {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.GEMINI, "google", Status.VERIFIED),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "openai", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "google", Status.EXPERIMENTAL)));

        assertThat(registry.offeredPairs()).extracting(pair -> pair.entry() + "/" + pair.vendor())
            .containsExactly("CHAT/google", "MESSAGES/anthropic", "MESSAGES/openai", "GEMINI/google");
    }

    @Test
    void aPairIsLookedUpByTheNamesAGateUses() {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.VERIFIED)));

        assertThat(registry.status("anthropic", "OPENAI_CHAT", "MESSAGES")).isEqualTo(Status.VERIFIED);
        assertThat(registry.status("anthropic", "OPENAI_CHAT", "CHAT")).isEqualTo(Status.NOT_OFFERED);
    }

    @Test
    void aNameThisCodeDoesNotKnowIsNotOffered() {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.VERIFIED)));

        // A newer release may have gates for a protocol, an entry or a vendor this one has never heard of.
        assertThat(registry.status("anthropic", "OPENAI_RESPONSES", "MESSAGES")).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.status("anthropic", "OPENAI_CHAT", "BATCH")).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.status("anthropic", "OPENAI_CHAT", "messages")).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.status("mistral", "OPENAI_CHAT", "MESSAGES")).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.status(null, null, null)).isEqualTo(Status.NOT_OFFERED);
        assertThat(registry.status(null, "OPENAI_CHAT", "MESSAGES")).isEqualTo(Status.NOT_OFFERED);
    }

    @Test
    void aPairIsListedOnceAndNeverAsNotOffered() {
        Registration registration = new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL);

        assertThatThrownBy(() -> new PairRegistry(List.of(registration, registration)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("listed twice");
        assertThatThrownBy(() -> new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.NOT_OFFERED))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("left out");
        assertThatThrownBy(() -> new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "deepseek", Status.VERIFIED))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deepseek");
        assertThatThrownBy(() -> new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyPairThatCanBeNamedIsListedOnce() {
        assertThat(PairRegistry.allPairs()).hasSize(SourceKind.values().length * Entry.values().length * PairRegistry.VENDORS.size())
            .doesNotHaveDuplicates();
    }

    @Test
    void theRegistryAndTheGateVocabularyNameTheSamePairs() {
        // The gates, the validation of them and the console speak in strings and the tiers' own entry enum; the registry
        // speaks in its enums. They must stay the same set, or a gate could name a pair the registry cannot be asked.
        assertThat(Arrays.stream(Entry.values()).map(Enum::name).toList())
            .containsExactlyElementsOf(SourceTierRuntimeInfo.ENTRY_ENDPOINTS)
            .containsExactlyElementsOf(Arrays.stream(TierEntry.values()).map(Enum::name).toList());
        assertThat(SourceTierRuntimeInfo.CROSS_PROTOCOL_VENDORS).containsExactlyElementsOf(PairRegistry.VENDORS);
        assertThat(SourceKind.OPENAI_CHAT_RELAY.sourceProtocol()).isEqualTo(SourceTierRuntimeInfo.OPENAI_CHAT);
        assertThat(SourceKind.ofProtocol("OPENAI_CHAT")).contains(SourceKind.OPENAI_CHAT_RELAY);
        assertThat(SourceKind.ofProtocol("OPENAI_RESPONSES")).isEmpty();
        assertThat(new SourceTierRuntimeInfo().pairs()).extracting(pair -> pair.vendor() + "/" + pair.entryEndpoint())
            .containsExactlyInAnyOrderElementsOf(PairRegistry.allPairs().stream()
                .map(pair -> pair.vendor() + "/" + pair.entry()).toList());
    }

    @Test
    void theRuntimeReportsWhatTheRegistryLists() {
        PairRegistry registry = new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.VERIFIED)));
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(true, true, true, true, SourceTierRuntimeInfo.statusesOf(registry));

        assertThat(runtime.pairStatus("anthropic", "OPENAI_CHAT", "CHAT")).isEqualTo(PairStatus.EXPERIMENTAL);
        assertThat(runtime.pairStatus("anthropic", "OPENAI_CHAT", "MESSAGES")).isEqualTo(PairStatus.VERIFIED);
        assertThat(runtime.pairStatus("anthropic", "OPENAI_CHAT", "RESPONSES")).isEqualTo(PairStatus.NOT_OFFERED);
        assertThat(runtime.pairStatus("openai", "OPENAI_CHAT", "CHAT")).isEqualTo(PairStatus.NOT_OFFERED);
        assertThat(runtime.pairStatus("anthropic", "OPENAI_RESPONSES", "CHAT")).isEqualTo(PairStatus.NOT_OFFERED);
        // The status names of the two enums are the same set, which the conversion relies on.
        assertThat(Arrays.stream(Status.values()).map(Enum::name).toList())
            .containsExactlyElementsOf(Arrays.stream(PairStatus.values()).map(Enum::name).toList());
    }

    @Test
    void theDefaultRuntimeAsksTheDefaultRegistry() {
        assertThat(new SourceTierRuntimeInfo().pairs()).allSatisfy(pair ->
            assertThat(new SourceTierRuntimeInfo().pairStatus(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint()))
                .isEqualTo(PairStatus.valueOf(PairRegistry.defaults().status(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint()).name())));
    }

    @Test
    void aRuntimeNeedsARegistry() {
        assertThatThrownBy(() -> SourceTierRuntimeInfo.statusesOf(null)).isInstanceOf(NullPointerException.class);
    }
}
