package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.model.ModelTierListing;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * Which tier the public model list prices a model under (design 5.7): the first tier that has an account able to serve
 * it, and nothing at all for a deployment that does not run tiers.
 */
class SourceTierModelListingTest {

    private static final AccountAllocationCategory OFFICIAL = AccountAllocationCategory.OFFICIAL_API_KEY;
    private static final AccountAllocationCategory CODEX = AccountAllocationCategory.CODEX;
    private static final AccountAllocationCategory KIRO = AccountAllocationCategory.KIRO;

    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(catalog);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);

    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key", null);
    private final ProxyAccountEntity kiro = account("kiro", "BuilderId", "idc", null);
    private final ProxyAccountEntity officialOpenAi = account("openai-official", "OpenAI", "api-key", null);
    private final ProxyAccountEntity codex = account("codex", "OpenAI", "codex", null);

    private ModelTierListing listing(boolean nativeTiers, SourceTierSnapshot snapshot, ProxyAccountEntity... accounts) {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(nativeTiers, false, false, pair -> PairStatus.NOT_OFFERED);
        when(registry.loaded()).thenReturn(true);
        when(registry.snapshot()).thenReturn(snapshot);
        when(accountPool.cachedAccounts()).thenReturn(List.of(accounts));
        return new SourceTierModelListing(registry, runtime, accountPool, keyResolver).current();
    }

    private ModelDtos.ModelListItem model(String id) {
        return catalog.modelList(1L).data().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aReleaseThatCannotRunTiersListsEveryModelAsItAlwaysDidWithoutLookingAtAnything() {
        ModelTierListing listing = listing(false, snapshot(true, false, anthropicTiers(), List.of()));

        assertThat(listing).isSameAs(ModelTierListing.NONE);
        verifyNoInteractions(accountPool);
    }

    @Test
    void aDeploymentWithoutAVendorSwitchedOnLooksAtNoAccounts() {
        for (SourceTierSnapshot snapshot : List.of(
            SourceTierSnapshot.EMPTY,
            snapshot(false, true, anthropicTiers(), List.of()),
            snapshot(true, true, List.of(tier("anthropic", "official", 1, "官方", "1", false, OFFICIAL)), List.of()))) {
            assertThat(listing(true, snapshot, officialAnthropic)).isSameAs(ModelTierListing.NONE);
        }
        verifyNoInteractions(accountPool);
    }

    @Test
    void beforeTheConfigurationHasEverLoadedTheListIsTheStandardOne() {
        when(registry.loaded()).thenReturn(false);
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(true, false, false, pair -> PairStatus.NOT_OFFERED);

        assertThat(new SourceTierModelListing(registry, runtime, accountPool, keyResolver).current())
            .isSameAs(ModelTierListing.NONE);
    }

    @Test
    void aModelOfAnotherVendorIsNotUnderTiers() {
        ModelTierListing listing = listing(true, snapshot(true, true, anthropicTiers(), List.of()), officialAnthropic, kiro);

        assertThat(listing.of(model("gpt-5.5"))).isEmpty();
        assertThat(listing.of(model("claude-sonnet-4.5"))).isPresent();
    }

    @Test
    void aModelIsPricedByTheFirstTierThatHoldsAnAccount() {
        SourceTierSnapshot tiers = snapshot(true, false, anthropicTiers(), List.of());

        assertThat(listing(true, tiers, officialAnthropic, kiro).of(model("claude-sonnet-4.5")).orElseThrow().headlineFactor())
            .isEqualByComparingTo("1");
        // With no official key at all the first tier cannot serve, so the model is listed at the one that can.
        assertThat(listing(true, tiers, kiro).of(model("claude-sonnet-4.5")).orElseThrow().headlineFactor())
            .isEqualByComparingTo("0.6");
        // Nothing holds an account: a request is planned from the first tier all the same, and it is the one listed.
        assertThat(listing(true, tiers).of(model("claude-sonnet-4.5")).orElseThrow().headlineFactor()).isEqualByComparingTo("1");
    }

    @Test
    void aModelOnlyCodexServesIsPricedByTheFirstTierThatHoldsCodexAccounts() {
        SourceTierSnapshot tiers = snapshot("openai", true, false, openAiTiers(), List.of());
        ModelTierListing listing = listing(true, tiers, officialOpenAi, codex);

        assertThat(listing.of(model("gpt-5.5")).orElseThrow().headlineFactor())
            .as("gpt-5.5 is a Codex-only model, and the official keys in front of Codex do not serve it")
            .isEqualByComparingTo("0.5");
        assertThat(listing.of(model("gpt-5.4")).orElseThrow().headlineFactor()).isEqualByComparingTo("1");
    }

    @Test
    void aDisabledTierIsNeverListed() {
        List<Tier> tiers = List.of(
            tier("anthropic", "official", 1, "官方", "1", false, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro", "0.6", true, KIRO));

        ModelTierListing.Listing listed = listing(true, snapshot(true, true, tiers, List.of()), officialAnthropic, kiro)
            .of(model("claude-sonnet-4.5")).orElseThrow();

        assertThat(listed.headlineFactor()).isEqualByComparingTo("0.6");
        assertThat(listed.tiers()).extracting(ModelTierListing.Tier::id).containsExactly("kiro");
    }

    @Test
    void theTiersAreNamedOnlyWhenTheVendorListsThemAndOnlyThoseThatCanServeTheModel() {
        List<Tier> tiers = List.of(
            tier("openai", "official", 1, "official", "1", true, OFFICIAL),
            tier("openai", "codex", 2, "codex", "0.5", true, CODEX));

        ModelTierListing hidden = listing(true, snapshot("openai", true, false, tiers, List.of()), officialOpenAi, codex);
        assertThat(hidden.of(model("gpt-5.4")).orElseThrow().tiers()).isEmpty();

        ModelTierListing listed = listing(true, snapshot("openai", true, true, tiers, List.of()), officialOpenAi, codex);
        assertThat(listed.of(model("gpt-5.4")).orElseThrow().tiers())
            .extracting(ModelTierListing.Tier::id, ModelTierListing.Tier::level, ModelTierListing.Tier::name, ModelTierListing.Tier::selectable)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("official", 1, "official", true),
                org.assertj.core.groups.Tuple.tuple("codex", 2, "codex", true));
        assertThat(listed.of(model("gpt-5.5")).orElseThrow().tiers()).extracting(ModelTierListing.Tier::id)
            .as("only the tier with Codex accounts serves the Codex-only model").containsExactly("codex");
    }

    @Test
    void aRelayRegisteredForAnotherProtocolDoesNotHoldANativeTier() {
        ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        List<Member> members = List.of(new Member("anthropic", "relay", "official", "OPENAI_CHAT", true, false, false, false,
            false, true, "NONE", 540, true, true));
        ModelTierListing.Listing listed = listing(true, snapshot(true, true, anthropicTiers(), members), relay, kiro)
            .of(model("claude-sonnet-4.5")).orElseThrow();

        // The relay is the first tier's only member, and it serves through its own cell, so the native model is listed by Kiro.
        assertThat(listed.headlineFactor()).isEqualByComparingTo("0.6");
    }

    // ---- fixtures ----

    private static SourceTierSnapshot snapshot(boolean enabled, boolean publicListing, List<Tier> tiers, List<Member> members) {
        return snapshot("anthropic", enabled, publicListing, tiers, members);
    }

    private static SourceTierSnapshot snapshot(String vendor, boolean enabled, boolean publicListing, List<Tier> tiers, List<Member> members) {
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig(vendor, enabled, false, publicListing, true, 16, true, 7L)),
            tiers, members, List.of(), List.of());
    }

    private static List<Tier> anthropicTiers() {
        return List.of(
            tier("anthropic", "official", 1, "官方", "1", true, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro反向代理", "0.6", true, KIRO));
    }

    private static List<Tier> openAiTiers() {
        return List.of(
            tier("openai", "official", 1, "official", "1", true, OFFICIAL),
            tier("openai", "codex", 2, "codex", "0.5", true, CODEX));
    }

    private static Tier tier(String vendor, String tierId, int level, String name, String factor, boolean enabled,
        AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, name, new BigDecimal(factor), listed, true, enabled, null);
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
