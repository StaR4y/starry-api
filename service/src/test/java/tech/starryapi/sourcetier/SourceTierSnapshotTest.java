package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

class SourceTierSnapshotTest {

    private static final AccountAllocationCategory OFFICIAL = AccountAllocationCategory.OFFICIAL_API_KEY;
    private static final AccountAllocationCategory COMPATIBLE = AccountAllocationCategory.COMPATIBLE_API_KEY;

    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key", null);
    private final ProxyAccountEntity thirdPartyAnthropic =
        account("anthropic-gateway", "Anthropic", "api-key", "https://claude-gateway.example/v1");
    private final ProxyAccountEntity claudeOauth = account("claude-oauth", "Anthropic", "oauth", null);
    private final ProxyAccountEntity kiro = account("kiro", "BuilderId", "idc", null);
    private final ProxyAccountEntity officialOpenAi = account("openai-official", "OpenAI", "api-key", null);
    private final ProxyAccountEntity codex = account("codex", "OpenAI", "codex", null);
    private final ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private final ProxyAccountEntity deepSeek = account("deepseek", "DeepSeek", "api-key", null);

    // ---- empty ----

    @Test
    void theEmptySnapshotKnowsNothing() {
        SourceTierSnapshot empty = SourceTierSnapshot.EMPTY;

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.configVersion()).isZero();
        assertThat(empty.vendor("anthropic")).isEmpty();
        assertThat(empty.tiers("anthropic")).isEmpty();
        assertThat(empty.tier("anthropic", "official")).isEmpty();
        assertThat(empty.members("anthropic")).isEmpty();
        assertThat(empty.member("anthropic", "kiro")).isEmpty();
        assertThat(empty.models("anthropic", "kiro")).isEmpty();
        assertThat(empty.gates("anthropic")).isEmpty();
        assertThat(empty.exclusiveXpAccountIds()).isEmpty();
        assertThat(empty.tierOf("anthropic", officialAnthropic)).isEmpty();
    }

    @Test
    void aVendorWithoutTiersPlacesNobody() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(), List.of());

        assertThat(snapshot.isEmpty()).isFalse();
        assertThat(snapshot.tierOf("anthropic", officialAnthropic)).isEmpty();
        assertThat(snapshot.tierOf("openai", officialOpenAi)).isEmpty();
        assertThat(snapshot.tierOf("anthropic", null)).isEmpty();
    }

    @Test
    void theVendorIdsAreTheVendorsThatHaveAConfigurationRow() {
        assertThat(SourceTierSnapshot.EMPTY.vendorIds()).isEmpty();
        assertThat(snapshot(List.of(vendor("anthropic"), vendor("openai")), List.of(), List.of()).vendorIds())
            .containsExactlyInAnyOrder("anthropic", "openai");
    }

    // ---- tierOf ----

    @Test
    void aMemberRowPlacesTheAccountBeforeAnyCategoryDoes() {
        List<Tier> tiers = List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)
        );
        SourceTierSnapshot unpinned = snapshot(List.of(vendor("anthropic")), tiers, List.of());
        SourceTierSnapshot pinned = snapshot(List.of(vendor("anthropic")), tiers,
            List.of(nativeMember("anthropic", "anthropic-official", "kiro")));

        assertThat(tierId(unpinned.tierOf("anthropic", officialAnthropic))).isEqualTo("official");
        assertThat(tierId(pinned.tierOf("anthropic", officialAnthropic))).isEqualTo("kiro");
        assertThat(tierId(pinned.tierOf("anthropic", claudeOauth))).isEqualTo("kiro");
    }

    @Test
    void aCategoryPlacesTheAccountsOfItsKind() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)
        ), List.of());

        assertThat(tierId(snapshot.tierOf("anthropic", officialAnthropic))).isEqualTo("official");
        assertThat(tierId(snapshot.tierOf("anthropic", claudeOauth))).isEqualTo("kiro");
        assertThat(tierId(snapshot.tierOf("anthropic", kiro))).isEqualTo("kiro");
    }

    @Test
    void anAccountNoTierListsFallsToTheLowestEnabledTier() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO),
            tier("anthropic", "spare", 3, false)
        ), List.of());

        // A third-party Anthropic key is a compatible key: no tier lists that category. The disabled tier 3 is skipped.
        assertThat(tierId(snapshot.tierOf("anthropic", thirdPartyAnthropic))).isEqualTo("kiro");
        // The same goes for a Claude OAuth account while only KIRO is listed.
        assertThat(tierId(snapshot.tierOf("anthropic", claudeOauth))).isEqualTo("kiro");
    }

    @Test
    void aCategoryOnlyPlacesAccountsOfTheVendorsProviderFamily() {
        List<Tier> openAiTiers = List.of(
            tier("openai", "official", 1, true, OFFICIAL),
            tier("openai", "codex", 2, true, AccountAllocationCategory.CODEX)
        );
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("openai"), vendor("anthropic")),
            concat(openAiTiers, List.of(
                tier("anthropic", "official", 1, true, OFFICIAL),
                tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO)
            )), List.of());

        assertThat(tierId(snapshot.tierOf("openai", officialOpenAi))).isEqualTo("official");
        assertThat(tierId(snapshot.tierOf("openai", codex))).isEqualTo("codex");
        // A GPT model asked through /v1/messages is routed over Anthropic accounts, but an official Anthropic key is
        // in the same category as an official OpenAI key and must not become the OpenAI "official" pool.
        assertThat(tierId(snapshot.tierOf("openai", officialAnthropic))).isEqualTo("codex");
        assertThat(tierId(snapshot.tierOf("anthropic", officialAnthropic))).isEqualTo("official");
        assertThat(tierId(snapshot.tierOf("anthropic", officialOpenAi))).isEqualTo("kiro");
    }

    @Test
    void theCompatibleKeyCategoryIsSplitByProviderToo() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("deepseek"), vendor("zhipu")), List.of(
            tier("deepseek", "direct", 1, true, COMPATIBLE),
            tier("deepseek", "backup", 2, true),
            tier("zhipu", "relay", 1, true, COMPATIBLE),
            tier("zhipu", "backup", 2, true)
        ), List.of());

        assertThat(tierId(snapshot.tierOf("deepseek", deepSeek))).isEqualTo("direct");
        assertThat(tierId(snapshot.tierOf("deepseek", relay))).isEqualTo("backup");
        assertThat(tierId(snapshot.tierOf("deepseek", thirdPartyAnthropic))).isEqualTo("backup");
        // A custom catalog vendor is served by compatible keys, but DeepSeek has its own pool.
        assertThat(tierId(snapshot.tierOf("zhipu", relay))).isEqualTo("relay");
        assertThat(tierId(snapshot.tierOf("zhipu", deepSeek))).isEqualTo("backup");
        assertThat(tierId(snapshot.tierOf("zhipu", thirdPartyAnthropic))).isEqualTo("backup");
    }

    @Test
    void aTierThatIsSwitchedOffStillHoldsTheAccountsItClaims() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, false, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO)
        ), List.of(nativeMember("anthropic", "claude-oauth", "official")));

        // The caller sees the disabled tier, so it knows these accounts do not serve; they do not slide to tier 2.
        assertThat(snapshot.tierOf("anthropic", officialAnthropic)).hasValueSatisfying(found -> {
            assertThat(found.tierId()).isEqualTo("official");
            assertThat(found.enabled()).isFalse();
        });
        assertThat(tierId(snapshot.tierOf("anthropic", claudeOauth))).isEqualTo("official");
        assertThat(tierId(snapshot.tierOf("anthropic", kiro))).isEqualTo("kiro");
    }

    @Test
    void withNoEnabledTierTheFallbackPlacesNobody() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, false, OFFICIAL),
            tier("anthropic", "kiro", 2, false)
        ), List.of());

        assertThat(snapshot.tierOf("anthropic", thirdPartyAnthropic)).isEmpty();
        assertThat(tierId(snapshot.tierOf("anthropic", officialAnthropic))).isEqualTo("official");
    }

    @Test
    void aCategoryListedByTwoTiersBelongsToTheBetterOne() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "second", 2, true, OFFICIAL),
            tier("anthropic", "first", 1, true, OFFICIAL)
        ), List.of());

        assertThat(tierId(snapshot.tierOf("anthropic", officialAnthropic))).isEqualTo("first");
    }

    @Test
    void anAccountWithoutAnIdIsStillClassified() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO)
        ), List.of());
        ProxyAccountEntity anonymous = account(null, "Anthropic", "api-key", null);

        assertThat(tierId(snapshot.tierOf("anthropic", anonymous))).isEqualTo("official");
    }

    @Test
    void placementNamesTheRuleThatChoseTheTier() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO)
        ), List.of(nativeMember("anthropic", "claude-oauth", "official")));

        assertThat(snapshot.placementOf("anthropic", claudeOauth)).hasValueSatisfying(placement -> {
            assertThat(placement.rule()).isEqualTo(SourceTierSnapshot.Rule.MEMBER_ROW);
            assertThat(placement.tier().tierId()).isEqualTo("official");
        });
        assertThat(snapshot.placementOf("anthropic", officialAnthropic)).hasValueSatisfying(placement -> {
            assertThat(placement.rule()).isEqualTo(SourceTierSnapshot.Rule.CATEGORY);
            assertThat(placement.tier().tierId()).isEqualTo("official");
        });
        // No tier lists the compatible-key category, and an official OpenAI key is out of the vendor's family.
        assertThat(snapshot.placementOf("anthropic", thirdPartyAnthropic)).hasValueSatisfying(placement -> {
            assertThat(placement.rule()).isEqualTo(SourceTierSnapshot.Rule.LOWEST_TIER);
            assertThat(placement.tier().tierId()).isEqualTo("kiro");
        });
        assertThat(snapshot.placementOf("anthropic", officialOpenAi)).hasValueSatisfying(placement ->
            assertThat(placement.rule()).isEqualTo(SourceTierSnapshot.Rule.LOWEST_TIER));
        assertThat(snapshot.placementOf("openai", officialOpenAi)).isEmpty();
        assertThat(snapshot.placementOf("anthropic", null)).isEmpty();
    }

    @Test
    void tierOfIsThePlacementWithoutTheRule() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO)
        ), List.of(nativeMember("anthropic", "claude-oauth", "official")));

        for (ProxyAccountEntity account : List.of(claudeOauth, officialAnthropic, thirdPartyAnthropic, kiro, officialOpenAi)) {
            assertThat(snapshot.tierOf("anthropic", account))
                .as(account.getAccountId())
                .isEqualTo(snapshot.placementOf("anthropic", account).map(SourceTierSnapshot.Placement::tier));
        }
    }

    @Test
    void placedAccountsCountTheEnabledAccountsEachTierHoldsByAnyRule() {
        ProxyAccountEntity disabledKiro = account("kiro-off", "BuilderId", "idc", null);
        disabledKiro.setEnabled(false);
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, true, OFFICIAL),
            tier("anthropic", "kiro", 2, true, AccountAllocationCategory.KIRO)
        ), List.of(nativeMember("anthropic", "kiro", "official")));

        // kiro is pinned to official; claude-oauth and the third-party key are listed by no tier and fall into the lowest
        // one; the disabled account serves nobody; the official OpenAI key and the relay have no route to Claude models.
        assertThat(snapshot.placedAccounts("anthropic", List.of(
            officialAnthropic, kiro, claudeOauth, thirdPartyAnthropic, disabledKiro, officialOpenAi, relay, codex)))
            .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("official", 2, "kiro", 2));
        assertThat(snapshot.placedAccounts("anthropic", List.of())).isEmpty();
        assertThat(snapshot.placedAccounts("openai", List.of(officialAnthropic))).isEmpty();
    }

    @Test
    void anAnthropicKeyAndAKiroAccountAreInTheLowestTierOfAnotherVendorBecauseMessagesReachesThem() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("openai")), List.of(
            tier("openai", "official", 1, true, OFFICIAL),
            tier("openai", "codex", 2, true, AccountAllocationCategory.CODEX)
        ), List.of());

        assertThat(snapshot.placedAccounts("openai", List.of(officialOpenAi, codex, officialAnthropic, kiro, relay)))
            .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("official", 1, "codex", 3));
    }

    @Test
    void anExclusiveRelayIsCountedInNoTierOfAnyVendor() {
        ProxyAccountEntity glm = account("glm", "GLM", "api-key", "https://open.bigmodel.cn/api/paas/v4");
        List<Tier> tiers = List.of(tier("anthropic", "relay", 1, true), tier("moonshot", "direct", 1, true));
        SourceTierSnapshot exclusive = snapshot(List.of(vendor("anthropic"), vendor("moonshot")), tiers,
            List.of(relayMember("anthropic", "relay", "relay", true)));
        SourceTierSnapshot shared = snapshot(List.of(vendor("anthropic"), vendor("moonshot")), tiers,
            List.of(relayMember("anthropic", "relay", "relay", false)));

        // GLM's vendor has no row for the relay and would otherwise put it into its lowest tier.
        assertThat(exclusive.placedAccounts("moonshot", List.of(relay, glm))).containsExactlyEntriesOf(java.util.Map.of("direct", 1));
        assertThat(shared.placedAccounts("moonshot", List.of(relay, glm))).containsExactlyEntriesOf(java.util.Map.of("direct", 2));
    }

    @Test
    void anAccountIsAnExclusiveRelayByItsIdInAnyVendor() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(tier("anthropic", "relay", 1, true)),
            List.of(relayMember("anthropic", "relay", "relay", true), relayMember("anthropic", "shared", "relay", false)));

        assertThat(snapshot.isExclusiveRelay("relay")).isTrue();
        assertThat(snapshot.isExclusiveRelay("shared")).isFalse();
        assertThat(snapshot.isExclusiveRelay("somebody-else")).isFalse();
        assertThat(snapshot.isExclusiveRelay(null)).isFalse();
        assertThat(SourceTierSnapshot.EMPTY.isExclusiveRelay("relay")).isFalse();
    }

    @Test
    void theSnapshotSaysWhetherAnyVendorHasARelay() {
        List<Tier> tiers = List.of(tier("anthropic", "relay", 1, true));

        assertThat(SourceTierSnapshot.EMPTY.hasCrossProtocolMembers()).isFalse();
        assertThat(snapshot(List.of(vendor("anthropic")), tiers, List.of()).hasCrossProtocolMembers()).isFalse();
        // A native pin is no relay.
        assertThat(snapshot(List.of(vendor("anthropic")), tiers, List.of(nativeMember("anthropic", "pinned", "relay")))
            .hasCrossProtocolMembers()).isFalse();
        assertThat(snapshot(List.of(vendor("anthropic")), tiers, List.of(relayMember("anthropic", "relay", "relay", false)))
            .hasCrossProtocolMembers()).isTrue();
        // A row that points at no tier is not a member.
        assertThat(snapshot(List.of(vendor("anthropic")), tiers, List.of(relayMember("anthropic", "relay", "no-such-tier", false)))
            .hasCrossProtocolMembers()).isFalse();
    }

    @Test
    void aRelayIsOpenForAnEntryOnlyWhenItsTierAndVendorAreOnAndAGateOfItsProtocolIsNotOff() {
        List<Tier> tiers = List.of(tier("anthropic", "relay", 1, true));
        List<Member> members = List.of(relayMember("anthropic", "relay-1", "relay", true));
        PairGate messagesKeys = new PairGate("anthropic", "OPENAI_CHAT", "MESSAGES", "KEYS", List.of("key-1"));

        // Registered and not opened: the state of a relay between the day it is added and the day a gate is set.
        assertThat(SourceTierSnapshot.of(1L, List.of(vendor("anthropic")), tiers, members, List.of(), List.of())
            .hasOpenRelayFor("MESSAGES")).isFalse();
        SourceTierSnapshot opened = SourceTierSnapshot.of(1L, List.of(vendor("anthropic")), tiers, members, List.of(),
            List.of(messagesKeys, new PairGate("anthropic", "OPENAI_CHAT", "CHAT", "OFF", List.of())));
        assertThat(opened.hasOpenRelayFor("MESSAGES")).isTrue();
        assertThat(opened.hasOpenRelayFor("CHAT")).isFalse();
        assertThat(opened.hasOpenRelayFor("RESPONSES")).isFalse();
        assertThat(opened.hasOpenRelayFor(null)).isFalse();
        assertThat(opened.hasCrossProtocolMembers()).isTrue();
        // A vendor that is switched off, or a tier that is, plans nothing over its relays.
        assertThat(SourceTierSnapshot.of(1L, List.of(new VendorConfig("anthropic", false, false, false, true, 16, true, 1L)), tiers,
            members, List.of(), List.of(messagesKeys)).hasOpenRelayFor("MESSAGES")).isFalse();
        assertThat(SourceTierSnapshot.of(1L, List.of(vendor("anthropic")), List.of(tier("anthropic", "relay", 1, false)), members,
            List.of(), List.of(messagesKeys)).hasOpenRelayFor("MESSAGES")).isFalse();
        // The gate of another vendor opens nothing for this vendor's relay, nor a native pin's tier any relay.
        assertThat(SourceTierSnapshot.of(1L, List.of(vendor("anthropic"), vendor("openai")), tiers, members, List.of(),
            List.of(new PairGate("openai", "OPENAI_CHAT", "MESSAGES", "ON", List.of()))).hasOpenRelayFor("MESSAGES")).isFalse();
        assertThat(SourceTierSnapshot.of(1L, List.of(vendor("anthropic")), tiers, List.of(nativeMember("anthropic", "pinned", "relay")),
            List.of(), List.of(messagesKeys)).hasOpenRelayFor("MESSAGES")).isFalse();
        assertThat(SourceTierSnapshot.EMPTY.hasOpenRelayFor("MESSAGES")).isFalse();
    }

    @Test
    void aTierCanHoldNativeAccountsWhenARowNamesItItListsACategoryOrItIsTheLowestOneSwitchedOn() {
        List<Tier> tiers = List.of(
            tier("anthropic", "relay", 1, true),
            tier("anthropic", "listed", 2, true, AccountAllocationCategory.KIRO),
            tier("anthropic", "pinned", 3, true),
            tier("anthropic", "lowest", 4, true),
            tier("anthropic", "off", 5, false)
        );
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), tiers,
            List.of(relayMember("anthropic", "relay-1", "relay", true), nativeMember("anthropic", "spare", "pinned")));

        // A tier of relays alone: no category, no native row, and a tier below it takes the accounts nothing places.
        assertThat(snapshot.canHoldNativeAccounts("anthropic", "relay")).isFalse();
        assertThat(snapshot.canHoldNativeAccounts("anthropic", "listed")).isTrue();
        assertThat(snapshot.canHoldNativeAccounts("anthropic", "pinned")).isTrue();
        assertThat(snapshot.canHoldNativeAccounts("anthropic", "lowest")).isTrue();
        assertThat(snapshot.canHoldNativeAccounts("anthropic", "off")).isFalse();
        assertThat(snapshot.canHoldNativeAccounts("anthropic", "no-such-tier")).isFalse();
        assertThat(snapshot.canHoldNativeAccounts("openai", "relay")).isFalse();
        // Alone, the tier of relays is the lowest and takes the rest.
        assertThat(snapshot(List.of(vendor("anthropic")), List.of(tier("anthropic", "relay", 1, true)),
            List.of(relayMember("anthropic", "relay-1", "relay", true))).canHoldNativeAccounts("anthropic", "relay")).isTrue();
    }

    @Test
    void aCallerWithoutAKeyIsOnNoList() {
        PairGate gate = new PairGate("anthropic", "OPENAI_CHAT", "CHAT", "KEYS", List.of("key-1", PairGate.ANONYMOUS_KEY_ID));

        assertThat(gate.lists("key-1")).isTrue();
        assertThat(gate.lists("key-2")).isFalse();
        assertThat(gate.lists(null)).isFalse();
        assertThat(gate.lists(PairGate.ANONYMOUS_KEY_ID)).isFalse();
    }

    @Test
    void anUnconfiguredVendorHasTheDefaultsOfTheTable() {
        VendorConfig defaults = VendorConfig.defaults("anthropic");

        assertThat(defaults).isEqualTo(new VendorConfig("anthropic", false, false, false, true, 16, true, 0L));
        assertThat(VendorConfig.DEFAULT_REFUSAL_CAP).isEqualTo(16);
    }

    @Test
    void theProviderFamilyIsTheNativeChainOfTheVendor() {
        assertThat(SourceTierSnapshot.providerFamily("anthropic").test(officialAnthropic)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("anthropic").test(thirdPartyAnthropic)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("anthropic").test(claudeOauth)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("anthropic").test(kiro)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("anthropic").test(officialOpenAi)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("anthropic").test(relay)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("openai").test(officialOpenAi)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("openai").test(codex)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("openai").test(relay)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("openai").test(officialAnthropic)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("deepseek").test(deepSeek)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("deepseek").test(relay)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("google").test(kiro)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("google").test(officialAnthropic)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("moonshot").test(relay)).isTrue();
        assertThat(SourceTierSnapshot.providerFamily("moonshot").test(deepSeek)).isFalse();
        assertThat(SourceTierSnapshot.providerFamily("moonshot").test(officialOpenAi)).isFalse();
    }

    // ---- contents and indexes ----

    @Test
    void tiersComeBackBestLevelFirst() {
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic")), List.of(
            tier("anthropic", "c", 3, true),
            tier("anthropic", "a", 1, true),
            tier("anthropic", "b", 2, false)
        ), List.of());

        assertThat(snapshot.tiers("anthropic")).extracting(Tier::tierId).containsExactly("a", "b", "c");
        assertThat(snapshot.tier("anthropic", "b")).hasValueSatisfying(found -> assertThat(found.level()).isEqualTo(2));
        assertThat(snapshot.tier("anthropic", "missing")).isEmpty();
        assertThatThrownBy(() -> snapshot.tiers("anthropic").add(tier("anthropic", "d", 4, true)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exclusiveRelaysAreTheCrossProtocolMembersThatAreExclusive() {
        List<Tier> tiers = List.of(
            tier("anthropic", "relay", 1, true),
            tier("openai", "relay", 1, true)
        );
        List<Member> members = List.of(
            relayMember("anthropic", "relay-a", "relay", true),
            relayMember("anthropic", "relay-b", "relay", false),
            nativeMember("anthropic", "pinned", "relay"),
            relayMember("openai", "relay-c", "relay", true)
        );
        SourceTierSnapshot snapshot = snapshot(List.of(vendor("anthropic"), vendor("openai")), tiers, members);

        assertThat(snapshot.exclusiveXpAccountIds()).containsExactlyInAnyOrder("relay-a", "relay-c");
        assertThatThrownBy(() -> snapshot.exclusiveXpAccountIds().add("other"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.member("anthropic", "relay-a")).hasValueSatisfying(found -> {
            assertThat(found.crossProtocol()).isTrue();
            assertThat(found.protocol()).isEqualTo("OPENAI_CHAT");
        });
        assertThat(snapshot.member("anthropic", "pinned")).hasValueSatisfying(found ->
            assertThat(found.crossProtocol()).isFalse());
        assertThat(snapshot.members("anthropic")).extracting(Member::accountId)
            .containsExactlyInAnyOrder("relay-a", "relay-b", "pinned");
    }

    @Test
    void modelsAndGatesBelongToTheirVendorsAndMembers() {
        MemberModel sonnet = model("anthropic", "relay-a", "claude-sonnet-4.5");
        MemberModel haiku = model("anthropic", "relay-a", "claude-haiku-4.5");
        SourceTierSnapshot snapshot = SourceTierSnapshot.of(
            7L,
            List.of(vendor("anthropic")),
            List.of(tier("anthropic", "relay", 1, true)),
            List.of(relayMember("anthropic", "relay-a", "relay", true)),
            List.of(sonnet, haiku, model("anthropic", "someone-else", "claude-sonnet-4.5")),
            List.of(
                new PairGate("anthropic", "OPENAI_CHAT", "MESSAGES", "KEYS", List.of("key-1")),
                new PairGate("openai", "OPENAI_CHAT", "CHAT", "ON", List.of())
            )
        );

        assertThat(snapshot.configVersion()).isEqualTo(7L);
        assertThat(snapshot.models("anthropic", "relay-a")).containsExactly(sonnet, haiku);
        assertThat(snapshot.models("anthropic", "someone-else")).isEmpty();
        assertThat(snapshot.gates("anthropic")).singleElement().satisfies(gate -> {
            assertThat(gate.state()).isEqualTo("KEYS");
            assertThat(gate.allowKeyIds()).containsExactly("key-1");
        });
        assertThat(snapshot.gates("openai")).isEmpty();
    }

    @Test
    void rowsThatPointAtNothingAreIgnored() {
        SourceTierSnapshot snapshot = SourceTierSnapshot.of(
            1L,
            List.of(vendor("anthropic")),
            List.of(tier("anthropic", "relay", 1, true), tier("ghost", "relay", 1, true)),
            List.of(relayMember("anthropic", "to-nowhere", "no-such-tier", true), relayMember("ghost", "a", "relay", true)),
            List.of(model("anthropic", "to-nowhere", "claude-sonnet-4.5")),
            List.of(new PairGate("ghost", "OPENAI_CHAT", "CHAT", "ON", List.of()))
        );

        assertThat(snapshot.tiers("ghost")).isEmpty();
        assertThat(snapshot.members("anthropic")).isEmpty();
        assertThat(snapshot.members("ghost")).isEmpty();
        assertThat(snapshot.models("anthropic", "to-nowhere")).isEmpty();
        assertThat(snapshot.gates("ghost")).isEmpty();
        assertThat(snapshot.exclusiveXpAccountIds()).isEmpty();
    }

    // ---- the name a relay is tested with ----

    private static SourceTierSnapshot relays(boolean exclusive, MemberModel... models) {
        return SourceTierSnapshot.of(1L, List.of(vendor("anthropic"), vendor("openai")),
            List.of(tier("anthropic", "relay", 1, true), tier("openai", "relay", 1, true)),
            List.of(relayMember("anthropic", "relay-a", "relay", exclusive), nativeMember("anthropic", "pinned", "relay"),
                relayMember("openai", "relay-a", "relay", exclusive)),
            List.of(models), List.of());
    }

    private static MemberModel model(String vendor, String modelId, String wire, boolean enabled) {
        return new MemberModel(vendor, "relay-a", modelId, wire, 200_000, 8192, enabled, null, null);
    }

    @Test
    void anAccountThatIsNoRelayIsTestedAsItAlwaysWas() {
        SourceTierSnapshot snapshot = relays(true, model("anthropic", "claude-sonnet-4.5", "relay/sonnet", true));

        assertThat(snapshot.relayTestModel("pinned", "auto")).as("a native pin").isEmpty();
        assertThat(snapshot.relayTestModel("someone-else", "auto")).isEmpty();
        assertThat(snapshot.relayTestModel(null, "auto")).isEmpty();
        assertThat(SourceTierSnapshot.EMPTY.relayTestModel("relay-a", "auto")).isEmpty();
        assertThat(snapshot(List.of(vendor("anthropic")), List.of(), List.of()).relayTestModel("relay-a", "auto")).isEmpty();
        assertThat(relays(true).relayTestModel("relay-a", "auto")).as("a relay with no mapping at all").isEmpty();
    }

    @Test
    void aRelayIsTestedWithTheNameItServesTheFirstMappingUnder() {
        SourceTierSnapshot snapshot = relays(true, model("anthropic", "claude-sonnet-4.5", "relay/sonnet", true),
            model("anthropic", "claude-haiku-4.5", "relay/haiku", true), model("anthropic", "claude-opus-4.5", "relay/opus", false));

        // The models in the order of their catalog ids, the ones that are on first.
        for (String requested : new String[] {null, "", "  ", "auto", "AUTO", "simple-task"}) {
            assertThat(snapshot.relayTestModel("relay-a", requested)).as("%s", requested).hasValue("relay/haiku");
        }
        assertThat(relays(true, model("anthropic", "claude-aaa", "relay/off", false), model("anthropic", "claude-zzz", "relay/on", true))
            .relayTestModel("relay-a", "auto")).as("a mapping that is on comes first").hasValue("relay/on");
        assertThat(relays(true, model("anthropic", "claude-aaa", "relay/off", false)).relayTestModel("relay-a", "auto"))
            .as("and one that is off is better than none").hasValue("relay/off");
    }

    @Test
    void aModelThatTheRelayMapsIsTestedUnderItsOwnWireNameWhateverTheCase() {
        SourceTierSnapshot snapshot = relays(false, model("anthropic", "claude-sonnet-4.5", "relay/sonnet", true),
            model("anthropic", "claude-haiku-4.5", "relay/haiku", true));

        assertThat(snapshot.relayTestModel("relay-a", "claude-sonnet-4.5")).hasValue("relay/sonnet");
        assertThat(snapshot.relayTestModel("relay-a", " CLAUDE-SONNET-4.5 ")).hasValue("relay/sonnet");
        assertThat(snapshot.relayTestModel("relay-a", "relay/sonnet")).as("a wire name stays itself").hasValue("relay/sonnet");
    }

    @Test
    void aModelThatIsNotMappedFollowsWhetherTheRelayServesAnythingElse() {
        MemberModel sonnet = model("anthropic", "claude-sonnet-4.5", "relay/sonnet", true);

        // An exclusive relay serves nothing but its mappings, so whatever else is asked is a mapping too.
        assertThat(relays(true, sonnet).relayTestModel("relay-a", "glm-4.6")).hasValue("relay/sonnet");
        assertThat(relays(true, sonnet).relayTestModel("relay-a", "claude-opus-4.5")).hasValue("relay/sonnet");
        // One that also serves other models is tested with the model asked for, which is the test it always had.
        assertThat(relays(false, sonnet).relayTestModel("relay-a", "glm-4.6")).isEmpty();
        assertThat(relays(false, sonnet).relayTestModel("relay-a", "auto")).hasValue("relay/sonnet");
    }

    @Test
    void aRelayOfSeveralVendorsIsTestedWithTheFirstVendorsFirstMapping() {
        SourceTierSnapshot snapshot = relays(true, model("openai", "gpt-5", "relay/gpt", true), model("anthropic", "claude-sonnet-4.5", "relay/sonnet", true));

        assertThat(snapshot.relayTestModel("relay-a", "auto")).hasValue("relay/sonnet");
        assertThat(snapshot.relayTestModel("relay-a", "gpt-5")).hasValue("relay/gpt");
    }

    // ---- fixtures ----

    private static String tierId(java.util.Optional<Tier> tier) {
        return tier.map(Tier::tierId).orElse(null);
    }

    private static <T> List<T> concat(Collection<T> first, Collection<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static SourceTierSnapshot snapshot(List<VendorConfig> vendors, List<Tier> tiers, List<Member> members) {
        return SourceTierSnapshot.of(1L, vendors, tiers, members, List.of(), List.of());
    }

    private static VendorConfig vendor(String vendor) {
        return new VendorConfig(vendor, true, false, false, true, 16, true, 1L);
    }

    private static Tier tier(String vendor, String tierId, int level, boolean enabled, AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = categories.length == 0
            ? EnumSet.noneOf(AccountAllocationCategory.class)
            : EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, tierId, BigDecimal.ONE, listed, true, enabled, null);
    }

    private static Member nativeMember(String vendor, String accountId, String tierId) {
        return new Member(vendor, accountId, tierId, null, true, false, false, false, false, true, "NONE", 540, null, null);
    }

    private static Member relayMember(String vendor, String accountId, String tierId, boolean exclusive) {
        return new Member(vendor, accountId, tierId, "OPENAI_CHAT", exclusive, false, false, false, false, true, "NONE", 540,
            true, true);
    }

    private static MemberModel model(String vendor, String accountId, String modelId) {
        return new MemberModel(vendor, accountId, modelId, "relay/" + modelId, 200_000, 8192, true, null, null);
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setEnabled(true);
        return account;
    }
}
