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
