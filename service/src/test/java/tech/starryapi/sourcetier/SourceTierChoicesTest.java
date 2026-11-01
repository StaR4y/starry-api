package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.model.OfficialModelPricingProperties;
import tech.starryapi.model.OfficialModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the key form offers (GET /admin/source-tiers/me): the pools of the provider's vendor that a client may name, best
 * first, with their price worked out the way the public model list works it out. Who is shown which pools follows the
 * switch that lists them publicly, and nothing is offered where the release cannot run the choice.
 */
class SourceTierChoicesTest {

    private static final AdminPrincipal USER = new AdminPrincipal("user-1", "alice", "USER", Instant.parse("2026-10-25T09:00:00Z"), false);
    private static final AdminPrincipal ADMIN = new AdminPrincipal("admin-1", "root", "ADMIN", Instant.parse("2026-10-25T09:00:00Z"), false);

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(catalog);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing;

    SourceTierChoicesTest() {
        SystemSettingRepository settings = mock(SystemSettingRepository.class);
        when(settings.findById(anyString())).thenReturn(Mono.empty());
        pricing = new ModelPricingService(settings, mapper,
            new OfficialModelPricingService(WebClient.builder().build(), mapper, new OfficialModelPricingProperties()), catalog);
        when(payments.config()).thenReturn(Mono.just(new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "", "popup", false,
            new BigDecimal("0.10"), 10, null, null)));
    }

    private SourceTierChoices choices(boolean nativeTiers, boolean selectors, boolean loaded, SourceTierSnapshot snapshot) {
        when(registry.loaded()).thenReturn(loaded);
        when(registry.snapshot()).thenReturn(snapshot);
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(nativeTiers, selectors, false, false, pair -> PairStatus.NOT_OFFERED);
        return new SourceTierChoices(registry, runtime, keyResolver, catalog, pricing, payments);
    }

    private SourceTierChoices choices(SourceTierSnapshot snapshot) {
        return choices(true, true, true, snapshot);
    }

    private SourceTierDtos.MyTiers mine(SourceTierChoices choices, AdminPrincipal who, String provider) {
        return choices.forProvider(who, provider).block();
    }

    // ---- fixtures ----

    private static Tier tier(String vendor, String id, int level, String factor, boolean selectable, boolean enabled) {
        return new Tier(vendor, id, level, id.toUpperCase(), new BigDecimal(factor),
            EnumSet.noneOf(AccountAllocationCategory.class), selectable, enabled, "never shown");
    }

    private static SourceTierSnapshot snapshot(boolean enabled, boolean publicListing, Tier... tiers) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig(tiers[0].vendor(), enabled, false, publicListing, true, 16, true, 5L)),
            Arrays.asList(tiers), List.of(), List.of(), List.of());
    }

    /** official (1, x1), kiro (2, x0.6), internal (3, x0.3, nobody may choose it) and retired (4, x0.1, switched off). */
    private static SourceTierSnapshot anthropic(boolean enabled, boolean publicListing) {
        return snapshot(enabled, publicListing,
            tier("anthropic", "kiro", 2, "0.6", true, true),
            tier("anthropic", "official", 1, "1", true, true),
            tier("anthropic", "internal", 3, "0.3", false, true),
            tier("anthropic", "retired", 4, "0.1", true, false));
    }

    /** The first model of the vendor in the public list that has a price: the routing presets in front of it have none. */
    private ModelDtos.ModelListItem firstPricedModelOf(String vendor) {
        return catalog.modelList(0L).data().stream()
            .filter(model -> keyResolver.resolve(model.id(), model.id(), model.provider()).provider().equals(vendor))
            .filter(model -> pricing.applyPublicPricing(new ModelDtos.ModelListResponse("list", List.of(model), List.of(model)), BigDecimal.ONE)
                .block().data().getFirst().inputPricePerMillionTokens() != null)
            .findFirst().orElseThrow();
    }

    // ---- what is offered ----

    @Test
    void aUserIsOfferedTheSelectablePoolsOfAVendorThatListsThemBestFirstAndNothingElseAboutThem() {
        SourceTierDtos.MyTiers mine = mine(choices(anthropic(true, true)), USER, "Anthropic");

        assertThat(mine.provider()).isEqualTo("Anthropic");
        assertThat(mine.tiers()).extracting(SourceTierDtos.MyTier::id).containsExactly("official", "kiro");
        assertThat(mine.tiers()).extracting(SourceTierDtos.MyTier::level).containsExactly(1, 2);
        assertThat(mine.tiers()).extracting(SourceTierDtos.MyTier::name).containsExactly("OFFICIAL", "KIRO");
        assertThat(mine.tiers()).extracting(SourceTierDtos.MyTier::priceFactor)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("1"), new BigDecimal("0.6"));
    }

    @Test
    void thePriceIsThatOfTheFirstPricedModelOfTheProviderWithEachFactorApplied() {
        SourceTierDtos.MyTiers mine = mine(choices(anthropic(true, true)), USER, "Anthropic");
        ModelDtos.ModelListItem reference = firstPricedModelOf("anthropic");
        // The price the public model list shows for it, at the standard price.
        ModelDtos.ModelListItem listed = pricing.applyPublicPricing(
            new ModelDtos.ModelListResponse("list", List.of(reference), List.of(reference)), BigDecimal.ONE).block().data().getFirst();

        assertThat(mine.referenceModel()).isEqualTo(reference.id());
        assertThat(mine.tiers().get(0).pricing().inputPricePerMillionTokens())
            .isEqualByComparingTo(listed.inputPricePerMillionTokens());
        assertThat(mine.tiers().get(1).pricing().inputPricePerMillionTokens())
            .isEqualByComparingTo(listed.inputPricePerMillionTokens().multiply(new BigDecimal("0.6")));
        assertThat(mine.tiers().get(1).pricing().outputPricePerMillionTokens())
            .isEqualByComparingTo(listed.outputPricePerMillionTokens().multiply(new BigDecimal("0.6")));
    }

    @Test
    void theShapeCarriesNoAccountCategoryProtocolOrNote() {
        assertThat(Arrays.stream(SourceTierDtos.MyTier.class.getRecordComponents()).map(component -> component.getName()))
            .containsExactly("id", "level", "name", "priceFactor", "pricing");
        assertThat(Arrays.stream(SourceTierDtos.MyTiers.class.getRecordComponents()).map(component -> component.getName()))
            .containsExactly("provider", "referenceModel", "tiers");
    }

    @Test
    void aVendorThatKeepsItsPoolNamesToItselfShowsAUserNoneButAnAdministratorAll() {
        SourceTierChoices choices = choices(anthropic(true, false));

        SourceTierDtos.MyTiers user = mine(choices, USER, "Anthropic");
        SourceTierDtos.MyTiers admin = mine(choices, ADMIN, "Anthropic");

        assertThat(user.tiers()).isEmpty();
        assertThat(user.referenceModel()).isNull();
        assertThat(admin.tiers()).extracting(SourceTierDtos.MyTier::id).containsExactly("official", "kiro");
        assertThat(admin.referenceModel()).isNotNull();
    }

    @Test
    void aPoolNobodyMayChooseOrThatIsSwitchedOffIsOfferedToNoOne() {
        SourceTierChoices choices = choices(anthropic(true, true));

        assertThat(mine(choices, USER, "Anthropic").tiers()).extracting(SourceTierDtos.MyTier::id).doesNotContain("internal", "retired");
        assertThat(mine(choices, ADMIN, "Anthropic").tiers()).extracting(SourceTierDtos.MyTier::id).doesNotContain("internal", "retired");
    }

    @Test
    void theProviderMayBeWrittenAsTheCatalogOrTheKeyFormWritesIt() {
        SourceTierChoices choices = choices(anthropic(true, true));

        for (String provider : new String[] {"Anthropic", "anthropic", "  Anthropic ", "ANTHROPIC", "claude"}) {
            assertThat(mine(choices, USER, provider).tiers()).as(provider).hasSize(2);
        }
        assertThat(mine(choices, USER, " Anthropic ").provider()).as("echoed as it was asked, trimmed").isEqualTo("Anthropic");
    }

    @Test
    void anotherVendorIsOfferedItsOwnPoolsOnly() {
        SourceTierSnapshot both = SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, true, true, 16, true, 5L),
                new VendorConfig("openai", true, false, true, true, 16, true, 2L)),
            List.of(tier("anthropic", "kiro", 2, "0.6", true, true), tier("openai", "codex", 2, "0.5", true, true),
                tier("openai", "official", 1, "1", true, true)),
            List.of(), List.of(), List.of());
        SourceTierChoices choices = choices(both);

        assertThat(mine(choices, USER, "OpenAI").tiers()).extracting(SourceTierDtos.MyTier::id).containsExactly("official", "codex");
        assertThat(mine(choices, USER, "OpenAI").referenceModel()).isEqualTo(firstPricedModelOf("openai").id());
        assertThat(mine(choices, USER, "Anthropic").tiers()).extracting(SourceTierDtos.MyTier::id).containsExactly("kiro");
    }

    // ---- where there is nothing to choose ----

    @Test
    void aProviderWithoutTiersInUseIsOfferedNothing() {
        assertThat(mine(choices(anthropic(false, true)), USER, "Anthropic").tiers()).as("switched off").isEmpty();
        assertThat(mine(choices(anthropic(true, true)), USER, "OpenAI").tiers()).as("never configured").isEmpty();
        assertThat(mine(choices(SourceTierSnapshot.EMPTY), ADMIN, "Anthropic").tiers()).as("nothing configured").isEmpty();
        assertThat(mine(choices(anthropic(true, true)), ADMIN, "No Such Provider").tiers()).as("unknown provider").isEmpty();
        assertThat(mine(choices(anthropic(true, true)), ADMIN, "  ").tiers()).as("blank provider").isEmpty();
    }

    @Test
    void aVendorWhoseEveryPoolIsSwitchedOffOrReservedIsOfferedNothing() {
        SourceTierSnapshot none = snapshot(true, true,
            tier("anthropic", "internal", 1, "0.3", false, true), tier("anthropic", "retired", 2, "0.1", true, false));

        SourceTierDtos.MyTiers mine = mine(choices(none), ADMIN, "Anthropic");

        assertThat(mine.tiers()).isEmpty();
        assertThat(mine.referenceModel()).isNull();
    }

    @Test
    void aReleaseThatCannotChooseOrCannotRunTiersOffersNothingEvenIfTheyAreConfigured() {
        assertThat(mine(choices(true, false, true, anthropic(true, true)), ADMIN, "Anthropic").tiers()).as("no selectors").isEmpty();
        assertThat(mine(choices(false, true, true, anthropic(true, true)), ADMIN, "Anthropic").tiers()).as("no native tiers").isEmpty();
    }

    @Test
    void beforeTheConfigurationHasLoadedTheFormOffersNothingInsteadOfFailing() {
        assertThat(mine(choices(true, true, false, anthropic(true, true)), ADMIN, "Anthropic").tiers()).isEmpty();
    }
}
