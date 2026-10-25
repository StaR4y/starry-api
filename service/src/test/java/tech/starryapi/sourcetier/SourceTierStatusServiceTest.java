package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.account.allocation.AccountCandidateRanker;
import tech.starryapi.account.allocation.AccountCandidateRanker.AccountLoad;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.sourcetier.SourceTierStatusService.Served;

/**
 * What the status page reports: every account of a tier counted once by the layer that keeps it from serving, the tiers
 * this node would serve from, how the last 15 minutes were served, and where each account sits.
 */
class SourceTierStatusServiceTest {

    private static final AccountAllocationCategory OFFICIAL = AccountAllocationCategory.OFFICIAL_API_KEY;
    private static final AccountAllocationCategory KIRO = AccountAllocationCategory.KIRO;
    private static final AccountAllocationCategory CLAUDE_OAUTH = AccountAllocationCategory.CLAUDE_OAUTH;
    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(catalog);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final AccountCandidateRanker ranker = mock(AccountCandidateRanker.class);

    /** What the account cache holds, what the table says is switched off, and the rows of the request log. */
    private final List<ProxyAccountEntity> enabled = new ArrayList<>();
    private final List<ProxyAccountEntity> disabled = new ArrayList<>();
    private final List<Served> served = new ArrayList<>();
    private final Set<String> suspended = new HashSet<>();
    private final Map<String, AccountLoad> loads = new HashMap<>();
    private final List<Instant> logReadSince = new ArrayList<>();

    private final ProxyAccountEntity officialA = account("official-a", "Anthropic", "api-key", null);
    private final ProxyAccountEntity officialB = account("official-b", "Anthropic", "api-key", null);
    private final ProxyAccountEntity officialC = account("official-c", "Anthropic", "api-key", null);
    private final ProxyAccountEntity officialD = account("official-d", "Anthropic", "api-key", null);
    private final ProxyAccountEntity kiroA = account("kiro-a", "BuilderId", "idc", null);
    private final ProxyAccountEntity kiroB = account("kiro-b", "BuilderId", "idc", null);

    private SourceTierStatusService service(boolean nativeTiers) {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(nativeTiers, false, false, pair -> PairStatus.NOT_OFFERED);
        when(accountPool.cachedAccounts()).thenReturn(enabled);
        doAnswer(call -> !suspended.contains(((ProxyAccountEntity) call.getArgument(0)).getAccountId()))
            .when(accountPool).isUsable(any());
        when(ranker.loads(any())).thenReturn(Mono.just(loads));
        SourceTierStatusService.Reads reads = new SourceTierStatusService.Reads() {
            @Override
            public Mono<List<ProxyAccountEntity>> disabledAccounts() {
                return Mono.just(disabled);
            }

            @Override
            public Mono<List<ProxyAccountEntity>> accounts() {
                List<ProxyAccountEntity> all = new ArrayList<>(enabled);
                all.addAll(disabled);
                all.sort(java.util.Comparator.comparing(ProxyAccountEntity::getAccountId));
                return Mono.just(all);
            }

            @Override
            public Mono<List<Served>> served(Instant since) {
                logReadSince.add(since);
                return Mono.just(served);
            }
        };
        return new SourceTierStatusService(registry, runtime, new SourceTierValidator(catalog, keyResolver, runtime), accountPool,
            ranker, reads, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- the layers ----

    @Test
    void everyAccountOfATierIsCountedOnceByTheFirstLayerThatKeepsItFromServing() {
        loaded(snapshot(true, anthropicTiers(), List.of()));
        enabled.addAll(List.of(officialA, officialB, officialC, officialD, kiroA));
        disabled.add(kiroB);
        suspended.add("official-b");
        loads.put("official-c", new AccountLoad("COOLDOWN", false, false));
        loads.put("official-d", new AccountLoad("HEALTHY", true, true));
        loads.put("official-a", new AccountLoad("HEALTHY", true, false));

        SourceTierDtos.Status status = service(true).status().block();

        assertThat(status.at()).isEqualTo(NOW);
        assertThat(status.configVersion()).isEqualTo(7L);
        assertThat(status.windowMinutes()).isEqualTo(15);
        SourceTierDtos.VendorStatus anthropic = status.vendors().getFirst();
        assertThat(anthropic.vendor()).isEqualTo("anthropic");
        assertThat(anthropic.label()).isEqualTo("Anthropic");
        assertThat(anthropic.serving()).isTrue();
        SourceTierDtos.TierStatus official = anthropic.tiers().get(0);
        assertThat(official.accounts()).isEqualTo(new SourceTierDtos.TierAccounts(0, 1, 1, 1, 1));
        assertThat(official.serves()).isTrue();
        SourceTierDtos.TierStatus kiro = anthropic.tiers().get(1);
        assertThat(kiro.accounts()).isEqualTo(new SourceTierDtos.TierAccounts(1, 0, 0, 0, 1));
        assertThat(kiro.serves()).isTrue();
    }

    @Test
    void aTierWithoutAReadyAccountDoesNotServeAndASwitchedOffTierNeverDoes() {
        List<Tier> tiers = List.of(
            tier("anthropic", "official", 1, "官方", "1", true, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro", "0.6", false, KIRO, CLAUDE_OAUTH));
        loaded(snapshot(true, tiers, List.of()));
        enabled.addAll(List.of(officialA, kiroA));
        loads.put("official-a", new AccountLoad("HEALTHY", true, true));

        List<SourceTierDtos.TierStatus> statuses = service(true).status().block().vendors().getFirst().tiers();

        assertThat(statuses.get(0).serves()).as("its only account is at its limit").isFalse();
        assertThat(statuses.get(0).accounts().busy()).isEqualTo(1);
        assertThat(statuses.get(1).serves()).as("the tier is switched off, though its account is ready").isFalse();
        assertThat(statuses.get(1).accounts().ready()).isEqualTo(1);
    }

    @Test
    void nothingServesWhileTheVendorIsSwitchedOffOrTheReleaseCannotRunTiers() {
        enabled.addAll(List.of(officialA, kiroA));
        loaded(snapshot(false, anthropicTiers(), List.of()));
        SourceTierDtos.VendorStatus off = service(true).status().block().vendors().getFirst();
        assertThat(off.enabled()).isFalse();
        assertThat(off.serving()).isFalse();
        assertThat(off.tiers()).allSatisfy(tier -> assertThat(tier.serves()).isFalse());
        assertThat(off.tiers().getFirst().accounts().ready()).as("the accounts are counted all the same").isEqualTo(1);

        loaded(snapshot(true, anthropicTiers(), List.of()));
        SourceTierDtos.VendorStatus noRuntime = service(false).status().block().vendors().getFirst();
        assertThat(noRuntime.enabled()).isTrue();
        assertThat(noRuntime.serving()).isFalse();
        assertThat(noRuntime.tiers()).allSatisfy(tier -> assertThat(tier.serves()).isFalse());
    }

    @Test
    void anAccountTheVendorsModelsCannotReachIsInNoTierOfIt() {
        loaded(snapshot(true, anthropicTiers(), List.of()));
        ProxyAccountEntity deepSeek = account("deepseek", "DeepSeek", "api-key", null);
        ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        enabled.addAll(List.of(officialA, deepSeek, relay));

        List<SourceTierDtos.TierStatus> tiers = service(true).status().block().vendors().getFirst().tiers();

        assertThat(tiers.get(0).accounts().ready() + tiers.get(1).accounts().ready()).isEqualTo(1);
    }

    @Test
    void aRelayRegisteredForAnotherProtocolIsNotCountedAsANativeAccountOfItsTier() {
        ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        Member registered = new Member("anthropic", "relay", "official", "OPENAI_CHAT", true, false, false, false, false, true,
            "NONE", 540, true, true);
        loaded(snapshot(true, anthropicTiers(), List.of(registered)));
        enabled.addAll(List.of(relay, kiroA));

        List<SourceTierDtos.TierStatus> tiers = service(true).status().block().vendors().getFirst().tiers();

        assertThat(tiers.get(0).accounts()).isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 0, 0, 0));
    }

    // ---- the window ----

    @Test
    void theWindowCountsServedPinnedAndFailedAttemptsAndHowManyAutoRequestsWereServedBelowTheBestTier() {
        loaded(snapshot(true, anthropicTiers(), List.of()));
        served.addAll(List.of(
            new Served("anthropic:official", "AUTO", true, 70),
            new Served("anthropic:official", "AUTO", false, 4),
            new Served("anthropic:official", "PINNED", true, 6),
            new Served("anthropic:kiro", "AUTO", true, 30),
            new Served("anthropic:kiro", "PINNED", true, 5),
            new Served("anthropic:kiro", "AUTO", false, 2),
            new Served("anthropic:gone", "AUTO", true, 999),
            new Served("openai:official", "AUTO", true, 999)));

        SourceTierDtos.VendorStatus anthropic = service(true).status().block().vendors().getFirst();

        assertThat(logReadSince).containsExactly(NOW.minus(Duration.ofMinutes(15)));
        assertThat(anthropic.autoServed()).isEqualTo(100);
        assertThat(anthropic.degraded()).isEqualTo(30);
        SourceTierDtos.TierStatus official = anthropic.tiers().get(0);
        assertThat(official.served()).isEqualTo(76);
        assertThat(official.servedPinned()).isEqualTo(6);
        assertThat(official.failed()).isEqualTo(4);
        SourceTierDtos.TierStatus kiro = anthropic.tiers().get(1);
        assertThat(kiro.served()).isEqualTo(35);
        assertThat(kiro.servedPinned()).isEqualTo(5);
        assertThat(kiro.failed()).isEqualTo(2);
    }

    @Test
    void theBestTierIsTheFirstOneThatIsSwitchedOn() {
        List<Tier> tiers = List.of(
            tier("anthropic", "official", 1, "官方", "1", false, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro", "0.6", true, KIRO, CLAUDE_OAUTH));
        loaded(snapshot(true, tiers, List.of()));
        served.add(new Served("anthropic:kiro", "AUTO", true, 12));

        SourceTierDtos.VendorStatus anthropic = service(true).status().block().vendors().getFirst();

        assertThat(anthropic.autoServed()).isEqualTo(12);
        assertThat(anthropic.degraded()).as("serving from the best tier that is on is not a downgrade").isZero();
    }

    // ---- what is reported ----

    @Test
    void onlyVendorsThatHaveTiersAreReportedInTheOrderOfTheCatalog() {
        List<Tier> tiers = new ArrayList<>(anthropicTiers());
        tiers.add(tier("openai", "official", 1, "official", "1", true, OFFICIAL));
        loaded(SourceTierSnapshot.of(7L, List.of(
            new VendorConfig("openai", true, false, false, true, 16, true, 3L),
            new VendorConfig("anthropic", true, false, false, true, 16, true, 7L),
            new VendorConfig("deepseek", true, false, false, true, 16, true, 1L)), tiers, List.of(), List.of(), List.of()));

        assertThat(service(true).status().block().vendors()).extracting(SourceTierDtos.VendorStatus::vendor)
            .containsExactly("anthropic", "openai");
    }

    @Test
    void withoutAnyTierNothingIsReadFromTheAccountsOrTheLog() {
        loaded(SourceTierSnapshot.EMPTY);

        SourceTierDtos.Status status = service(true).status().block();

        assertThat(status.vendors()).isEmpty();
        assertThat(logReadSince).isEmpty();
        verifyNoInteractions(ranker);
    }

    @Test
    void beforeTheConfigurationHasEverLoadedThereIsNothingToReport() {
        SourceTierRegistry neverLoaded = new SourceTierRegistry(
            mock(DatabaseClient.class), mock(ReactiveTransactionManager.class), mock(SystemMaintenanceTaskGate.class));
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(true, false, false, pair -> PairStatus.NOT_OFFERED);
        SourceTierStatusService service = new SourceTierStatusService(neverLoaded, runtime,
            new SourceTierValidator(catalog, keyResolver, runtime), accountPool, ranker, mock(DatabaseClient.class));

        for (Mono<?> call : List.of(service.status(), service.placements())) {
            assertThatThrownBy(call::block).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE");
            });
        }
    }

    // ---- placements ----

    @Test
    void eachAccountIsPlacedByTheRuleThatChoseItsTierAndAccountsInNoTierAreLeftOut() {
        Member pinned = new Member("anthropic", "official-b", "kiro", null, true, false, false, false, false, true, "NONE",
            540, null, null);
        loaded(snapshot(true, anthropicTiers(), List.of(pinned)));
        ProxyAccountEntity deepSeek = account("deepseek", "DeepSeek", "api-key", null);
        ProxyAccountEntity thirdParty = account("third-party", "Anthropic", "api-key", "https://claude-gateway.example/v1");
        enabled.addAll(List.of(officialA, officialB, kiroA, deepSeek, thirdParty));
        disabled.add(kiroB);

        SourceTierDtos.Placements placements = service(true).placements().block();

        assertThat(placements.configVersion()).isEqualTo(7L);
        assertThat(placements.accounts()).extracting(SourceTierDtos.AccountPlacement::accountId)
            .containsExactly("kiro-a", "kiro-b", "official-a", "official-b", "third-party");
        assertThat(placement(placements, "official-a")).containsExactly(
            new SourceTierDtos.Placed("anthropic", "Anthropic", "official", 1, "官方", "CATEGORY", false, true));
        assertThat(placement(placements, "official-b")).containsExactly(
            new SourceTierDtos.Placed("anthropic", "Anthropic", "kiro", 2, "kiro反向代理", "MEMBER_ROW", false, true));
        assertThat(placement(placements, "kiro-b")).extracting(SourceTierDtos.Placed::tierId).containsExactly("kiro");
        assertThat(placement(placements, "third-party")).as("no tier lists its category: it falls into the lowest one")
            .containsExactly(new SourceTierDtos.Placed("anthropic", "Anthropic", "kiro", 2, "kiro反向代理", "LOWEST_TIER", false, true));
    }

    @Test
    void aRelayIsPlacedAsARelayAndAPlacementIsInactiveWhileItsVendorOrTierIsOff() {
        ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        Member registered = new Member("anthropic", "relay", "official", "OPENAI_CHAT", true, false, false, false, false, true,
            "NONE", 540, true, true);
        enabled.addAll(List.of(relay, kiroA));

        loaded(snapshot(false, anthropicTiers(), List.of(registered)));
        SourceTierDtos.Placements vendorOff = service(true).placements().block();
        assertThat(placement(vendorOff, "relay")).containsExactly(
            new SourceTierDtos.Placed("anthropic", "Anthropic", "official", 1, "官方", "MEMBER_ROW", true, false));
        assertThat(placement(vendorOff, "kiro-a")).allSatisfy(placed -> assertThat(placed.active()).isFalse());

        loaded(snapshot(true, anthropicTiers(), List.of(registered)));
        assertThat(placement(service(false).placements().block(), "relay")).as("the release cannot run tiers")
            .allSatisfy(placed -> assertThat(placed.active()).isFalse());
        assertThat(placement(service(true).placements().block(), "relay")).allSatisfy(placed -> assertThat(placed.active()).isTrue());
    }

    @Test
    void withoutAnyTierThereAreNoPlacements() {
        loaded(SourceTierSnapshot.EMPTY);
        enabled.add(officialA);

        assertThat(service(true).placements().block().accounts()).isEmpty();
    }

    private static List<SourceTierDtos.Placed> placement(SourceTierDtos.Placements placements, String accountId) {
        return placements.accounts().stream().filter(account -> account.accountId().equals(accountId)).findFirst()
            .map(SourceTierDtos.AccountPlacement::tiers).orElse(List.of());
    }

    // ---- fixtures ----

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private static SourceTierSnapshot snapshot(boolean vendorEnabled, List<Tier> tiers, List<Member> members) {
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", vendorEnabled, false, false, true, 16, true, 7L)),
            tiers, members, List.of(), List.of());
    }

    private static List<Tier> anthropicTiers() {
        return List.of(
            tier("anthropic", "official", 1, "官方", "1", true, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro反向代理", "0.6", true, KIRO, CLAUDE_OAUTH));
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
