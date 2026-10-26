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
import java.util.function.Function;
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
import tech.starryapi.proxy.tier.XpSkip;
import tech.starryapi.proxy.tier.XpSkipCounts;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.sourcetier.SourceTierStatusService.FeatureRow;
import tech.starryapi.sourcetier.SourceTierStatusService.FigureRow;
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
    private final XpSkipCounts skipCounts = mock(XpSkipCounts.class);

    /** What the account cache holds, what the table says is switched off, and the rows of the request log. */
    private final List<ProxyAccountEntity> enabled = new ArrayList<>();
    private final List<ProxyAccountEntity> disabled = new ArrayList<>();
    private final List<Served> served = new ArrayList<>();
    /** The attempts sent to relays, as the log groups them, and how many times the log was asked for each. */
    private final List<FigureRow> relayFigures = new ArrayList<>();
    private final List<FigureRow> pairFigures = new ArrayList<>();
    private final List<FigureRow> memberFigures = new ArrayList<>();
    private final List<FeatureRow> features = new ArrayList<>();
    private final List<Instant> relayReadSince = new ArrayList<>();
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
        return service(nativeTiers, pair -> PairStatus.NOT_OFFERED);
    }

    private SourceTierStatusService service(boolean nativeTiers, java.util.function.Function<SourceTierRuntimeInfo.Pair, PairStatus> pairs) {
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(nativeTiers, false, false, pairs);
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

            @Override
            public Mono<List<FigureRow>> relayFigures(Instant since) {
                relayReadSince.add(since);
                return Mono.just(relayFigures);
            }

            @Override
            public Mono<List<FigureRow>> pairFigures(Instant since) {
                return Mono.just(pairFigures);
            }

            @Override
            public Mono<List<FigureRow>> memberFigures(Instant since) {
                return Mono.just(memberFigures);
            }

            @Override
            public Mono<List<FeatureRow>> relayFeatures(Instant since) {
                return Mono.just(features);
            }
        };
        return new SourceTierStatusService(registry, runtime, new SourceTierValidator(catalog, keyResolver, runtime), accountPool,
            ranker, reads, Clock.fixed(NOW, ZoneOffset.UTC), skipCounts);
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

        // It is counted as the relay it is, in its own layer; it is not a ready native account, and it is not asked for yet.
        assertThat(tiers.get(0).accounts()).isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 0, 0, 0, 1));
        assertThat(tiers.get(0).serves()).isFalse();
        assertThat(tiers.get(1).accounts().unopened()).as("a tier without a relay does not show the layer").isNull();
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
        // Registered, the vendor and the tier on, but nothing asks the relay yet: see the next test.
        assertThat(placement(service(true).placements().block(), "relay")).allSatisfy(placed -> assertThat(placed.active()).isFalse());
    }

    @Test
    void aRelayServesOnlyWhereAnOfferedPairHasAGateThatIsNotOffAndAMappingIsVerified() {
        ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        Member registered = new Member("anthropic", "relay", "official", "OPENAI_CHAT", true, false, false, false, false, true,
            "NONE", 540, true, true);
        SourceTierSnapshot.MemberModel unverified = new SourceTierSnapshot.MemberModel("anthropic", "relay", "claude-sonnet-4.5",
            "relay/sonnet", 200_000, 8192, true, null, null);
        SourceTierSnapshot.MemberModel verified = new SourceTierSnapshot.MemberModel("anthropic", "relay", "claude-sonnet-4.5",
            "relay/sonnet", 200_000, 8192, true, NOW, SourceTierValidator.mappingFingerprint(registered, unverified, relay));
        SourceTierSnapshot.MemberModel switchedOff = new SourceTierSnapshot.MemberModel("anthropic", "relay", "claude-sonnet-4.5",
            "relay/sonnet", 200_000, 8192, false, NOW, SourceTierValidator.mappingFingerprint(registered, unverified, relay));
        SourceTierSnapshot.MemberModel stale = new SourceTierSnapshot.MemberModel("anthropic", "relay", "claude-sonnet-4.5",
            "relay/sonnet", 200_000, 8192, true, NOW, "0".repeat(64));
        SourceTierSnapshot.PairGate keys = new SourceTierSnapshot.PairGate("anthropic", "OPENAI_CHAT", "MESSAGES", "KEYS", List.of("key-1"));
        SourceTierSnapshot.PairGate off = new SourceTierSnapshot.PairGate("anthropic", "OPENAI_CHAT", "MESSAGES", "OFF", List.of());
        enabled.addAll(List.of(relay, kiroA));
        java.util.function.Function<SourceTierRuntimeInfo.Pair, PairStatus> offered =
            pair -> pair.entryEndpoint().equals("MESSAGES") ? PairStatus.EXPERIMENTAL : PairStatus.NOT_OFFERED;

        // Everything there: the relay serves.
        loaded(withRelay(registered, verified, keys));
        assertThat(placement(service(true, offered).placements().block(), "relay")).allSatisfy(placed -> {
            assertThat(placed.relay()).isTrue();
            assertThat(placed.active()).isTrue();
        });
        // The tier of the relay and the native accounts beside it are not asked about any of this.
        assertThat(placement(service(true, offered).placements().block(), "kiro-a")).allSatisfy(placed -> assertThat(placed.active()).isTrue());

        // Each one missing: the gate is off or there is none, the pair is not offered for the entry of the gate, the mapping
        // is not verified, was verified for something else, or is switched off.
        for (SourceTierSnapshot snapshot : List.of(
            withRelay(registered, verified, off), withRelay(registered, verified), withRelay(registered, unverified, keys),
            withRelay(registered, stale, keys), withRelay(registered, switchedOff, keys), withRelay(registered, keys))) {
            loaded(snapshot);
            assertThat(placement(service(true, offered).placements().block(), "relay")).allSatisfy(placed -> assertThat(placed.active()).isFalse());
        }
        loaded(withRelay(registered, verified, keys));
        assertThat(placement(service(true).placements().block(), "relay")).as("no pair is offered").allSatisfy(placed -> assertThat(placed.active()).isFalse());
        // The account moved: what was verified is not this relay any more.
        relay.setBaseUrl("https://elsewhere.example/v1");
        assertThat(placement(service(true, offered).placements().block(), "relay")).allSatisfy(placed -> assertThat(placed.active()).isFalse());
    }

    private static SourceTierSnapshot withRelay(Member relay, Object... more) {
        List<SourceTierSnapshot.MemberModel> models = new ArrayList<>();
        List<SourceTierSnapshot.PairGate> gates = new ArrayList<>();
        for (Object one : more) {
            if (one instanceof SourceTierSnapshot.MemberModel model) {
                models.add(model);
            } else if (one instanceof SourceTierSnapshot.PairGate gate) {
                gates.add(gate);
            }
        }
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 7L)), anthropicTiers(),
            List.of(relay), models, gates);
    }

    @Test
    void anExclusiveRelayIsPlacedInTheVendorItServesAndInNoOtherVendor() {
        ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        ProxyAccountEntity glm = account("glm", "GLM", "api-key", "https://open.bigmodel.cn/api/paas/v4");
        Member registered = new Member("anthropic", "relay", "official", "OPENAI_CHAT", true, false, false, false, false, true,
            "NONE", 540, true, true);
        Member shared = new Member("anthropic", "relay", "official", "OPENAI_CHAT", false, false, false, false, false, true,
            "NONE", 540, true, true);
        enabled.addAll(List.of(relay, glm));

        loaded(withMoonshot(registered));
        SourceTierDtos.Placements exclusive = service(true).placements().block();
        // GLM's vendor has no row for the relay: it would fall into its lowest tier, where the relay serves nothing.
        assertThat(placement(exclusive, "relay")).extracting(SourceTierDtos.Placed::vendor).containsExactly("anthropic");
        assertThat(placement(exclusive, "glm")).extracting(SourceTierDtos.Placed::vendor).containsExactly("moonshot");

        loaded(withMoonshot(shared));
        SourceTierDtos.Placements notExclusive = service(true).placements().block();
        assertThat(placement(notExclusive, "relay")).extracting(SourceTierDtos.Placed::vendor)
            .containsExactlyInAnyOrder("anthropic", "moonshot");
    }

    /** The Anthropic configuration of these tests, with a second vendor whose models compatible keys such as GLM serve. */
    private static SourceTierSnapshot withMoonshot(Member relay) {
        List<Tier> tiers = new ArrayList<>(anthropicTiers());
        tiers.add(new Tier("moonshot", "direct", 1, "direct", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true,
            true, null));
        return SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 7L),
                new VendorConfig("moonshot", true, false, false, true, 16, true, 7L)),
            tiers, List.of(relay), List.of(), List.of());
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

    // ---- relays ----

    private final ProxyAccountEntity relayAccount = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private final Member relay = new Member("anthropic", "relay", "official", "OPENAI_CHAT", true, false, false, false, false, true,
        "NONE", 540, true, true);
    /** The pairs this release offers: Chat and Messages of the Anthropic and OpenAI vendors. */
    private final Function<SourceTierRuntimeInfo.Pair, PairStatus> offered = pair ->
        !pair.vendor().equals("google") && (pair.entryEndpoint().equals("CHAT") || pair.entryEndpoint().equals("MESSAGES"))
            ? PairStatus.EXPERIMENTAL : PairStatus.NOT_OFFERED;

    private SourceTierSnapshot.MemberModel mapping(String modelId, boolean enabled, boolean verified) {
        SourceTierSnapshot.MemberModel unverified = new SourceTierSnapshot.MemberModel("anthropic", "relay", modelId,
            "relay/" + modelId, 200_000, 8192, enabled, null, null);
        return verified
            ? new SourceTierSnapshot.MemberModel("anthropic", "relay", modelId, "relay/" + modelId, 200_000, 8192, enabled, NOW,
                SourceTierValidator.mappingFingerprint(relay, unverified, relayAccount))
            : unverified;
    }

    private SourceTierSnapshot.PairGate gate(String entry, String state, String... keys) {
        return new SourceTierSnapshot.PairGate("anthropic", "OPENAI_CHAT", entry, state, List.of(keys));
    }

    private static final XpSkipCounts.Key SKIP_LINT = new XpSkipCounts.Key("anthropic", "official", XpSkip.Reason.CONVERSION);

    private static SourceTierDtos.XpFigures figures(long attempts, long served, long interrupted, long conversionRejected, long estimated) {
        return new SourceTierDtos.XpFigures(attempts, served, attempts - served, interrupted, conversionRejected, 0, 0, estimated,
            0, 0, 1200L);
    }

    @Test
    void aRelayIsCountedInItsTierByTheFirstLayerThatKeepsItFromServingAndAnUnopenedOneIsToldFirst() {
        SourceTierSnapshot opened = withRelay(relay, mapping("claude-sonnet-4.5", true, true), gate("MESSAGES", "KEYS", "key-1"));
        SourceTierSnapshot closed = withRelay(relay, mapping("claude-sonnet-4.5", true, true), gate("MESSAGES", "OFF"));
        enabled.add(relayAccount);

        // Ready: the tier serves from it.
        loaded(opened);
        SourceTierDtos.TierStatus tier = service(true, offered).status().block().vendors().getFirst().tiers().getFirst();
        assertThat(tier.accounts()).isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 0, 0, 1, 0));
        assertThat(tier.serves()).isTrue();

        // At its limit, cooling down, suspended: as any account.
        loads.put("relay", new AccountLoad("HEALTHY", true, true));
        assertThat(layers(opened)).isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 0, 1, 0, 0));
        loads.put("relay", new AccountLoad("COOLDOWN", false, false));
        assertThat(layers(opened)).isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 1, 0, 0, 0));
        loads.clear();
        suspended.add("relay");
        assertThat(layers(opened)).isEqualTo(new SourceTierDtos.TierAccounts(0, 1, 0, 0, 0, 0));
        suspended.clear();

        // No gate lets requests through: unopened, whatever else is the matter with the account.
        loads.put("relay", new AccountLoad("HEALTHY", true, true));
        assertThat(layers(closed)).isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 0, 0, 0, 1));
        loads.clear();
        loaded(closed);
        assertThat(service(true, offered).status().block().vendors().getFirst().tiers().getFirst().serves()).isFalse();

        // Switched off: it is not in the cache and the table says so.
        enabled.clear();
        disabled.add(relayAccount);
        assertThat(layers(opened)).isEqualTo(new SourceTierDtos.TierAccounts(1, 0, 0, 0, 0, 0));
    }

    private SourceTierDtos.TierAccounts layers(SourceTierSnapshot snapshot) {
        loaded(snapshot);
        return service(true, offered).status().block().vendors().getFirst().tiers().getFirst().accounts();
    }

    @Test
    void theStatusOfAVendorWithRelaysShowsEachRelayItsPairsAndWhyRelaysWereLeftOutOfPlans() {
        loaded(withRelay(relay, mapping("claude-sonnet-4.5", true, true), mapping("claude-haiku-4.5", true, false),
            mapping("claude-opus-4.5", false, true), gate("MESSAGES", "KEYS", "key-1", "key-2")));
        enabled.add(relayAccount);
        relayFigures.add(new FigureRow("anthropic:official", "anthropic", "relay", null, figures(40, 36, 2, 1, 3)));
        relayFigures.add(new FigureRow("anthropic:official", "anthropic", "someone-else", null, figures(9, 9, 0, 0, 0)));
        pairFigures.add(new FigureRow(null, "anthropic", null, "MESSAGES", figures(40, 36, 2, 1, 3)));
        pairFigures.add(new FigureRow(null, "openai", null, "MESSAGES", figures(999, 999, 0, 0, 0)));
        features.addAll(List.of(
            new FeatureRow("anthropic", "MESSAGES", "image", 20), new FeatureRow("anthropic", "MESSAGES", "cache_control", 30),
            new FeatureRow("anthropic", "MESSAGES", "a", 1), new FeatureRow("anthropic", "MESSAGES", "b", 2),
            new FeatureRow("anthropic", "MESSAGES", "c", 3), new FeatureRow("anthropic", "MESSAGES", "d", 4),
            new FeatureRow("anthropic", "CHAT", "thinking", 5)));
        when(skipCounts.since()).thenReturn(NOW.minus(Duration.ofHours(3)));
        when(skipCounts.snapshot()).thenReturn(Map.of(
            SKIP_LINT, 2L,
            new XpSkipCounts.Key("anthropic", "official", XpSkip.Reason.FEATURE), 7L,
            new XpSkipCounts.Key("anthropic", "official", XpSkip.Reason.GATE), 100L,
            new XpSkipCounts.Key("openai", "official", XpSkip.Reason.GATE), 4L));

        SourceTierDtos.XpStatus xp = service(true, offered).status().block().vendors().getFirst().xp();

        assertThat(relayReadSince).containsExactly(NOW.minus(Duration.ofMinutes(15)));
        assertThat(xp.skipsSince()).isEqualTo(NOW.minus(Duration.ofHours(3)));
        assertThat(xp.relays()).containsExactly(new SourceTierDtos.XpRelay("official", "relay", "ready", 2, 1, figures(40, 36, 2, 1, 3)));
        assertThat(xp.pairs()).extracting(SourceTierDtos.XpPair::entryEndpoint, SourceTierDtos.XpPair::status,
                SourceTierDtos.XpPair::gate, SourceTierDtos.XpPair::allowedKeys)
            .as("only the pairs this release offers, or someone opened, or relays were sent to")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("CHAT", "EXPERIMENTAL", "OFF", 0),
                org.assertj.core.groups.Tuple.tuple("MESSAGES", "EXPERIMENTAL", "KEYS", 2));
        SourceTierDtos.XpPair messages = xp.pairs().get(1);
        assertThat(messages.figures()).isEqualTo(figures(40, 36, 2, 1, 3));
        assertThat(messages.degradedFeatures()).as("the five most frequent, most frequent first").containsExactly(
            new SourceTierDtos.FeatureCount("cache_control", 30), new SourceTierDtos.FeatureCount("image", 20),
            new SourceTierDtos.FeatureCount("d", 4), new SourceTierDtos.FeatureCount("c", 3), new SourceTierDtos.FeatureCount("b", 2));
        assertThat(xp.pairs().get(0).degradedFeatures()).containsExactly(new SourceTierDtos.FeatureCount("thinking", 5));
        assertThat(xp.skips()).as("this vendor's, by tier and in the order the reasons are met").containsExactly(
            new SourceTierDtos.XpSkipCount("official", "GATE", 100), new SourceTierDtos.XpSkipCount("official", "FEATURE", 7),
            new SourceTierDtos.XpSkipCount("official", "CONVERSION", 2));
    }

    @Test
    void aVendorWithoutRelaysHasNoRelayPartAndTheLogIsNotAskedAboutRelays() {
        loaded(snapshot(true, anthropicTiers(), List.of()));
        enabled.add(officialA);

        SourceTierDtos.VendorStatus vendor = service(true, offered).status().block().vendors().getFirst();

        assertThat(vendor.xp()).isNull();
        assertThat(relayReadSince).isEmpty();
        assertThat(vendor.tiers()).allSatisfy(tier -> assertThat(tier.accounts().unopened()).isNull());
    }

    // ---- the evidence ----

    @Test
    void theEvidenceHoldsEveryOfferedPairToTheStandardOverTheDaysAsked() {
        loaded(withRelay(relay, mapping("claude-sonnet-4.5", true, true), gate("MESSAGES", "KEYS", "key-1")));
        // 250 attempts through Messages: two refused as converted (0.8%), 10 estimated of 240 answered (4.17%), 4 interrupted (1.6%).
        pairFigures.add(new FigureRow(null, "anthropic", null, "MESSAGES", figures(250, 240, 4, 2, 10)));
        // Chat: too few, and one more refusal than the standard allows.
        pairFigures.add(new FigureRow(null, "anthropic", null, "CHAT", figures(100, 90, 0, 5, 0)));
        memberFigures.add(new FigureRow("anthropic:official", "anthropic", "relay", "MESSAGES", figures(250, 240, 4, 2, 10)));
        memberFigures.add(new FigureRow("anthropic:official", "anthropic", "relay", "CHAT", figures(100, 90, 0, 5, 0)));
        memberFigures.add(new FigureRow("anthropic:kiro", "anthropic", "relay-2", "MESSAGES", figures(1, 1, 0, 0, 0)));
        features.add(new FeatureRow("anthropic", "MESSAGES", "image", 12));
        when(skipCounts.since()).thenReturn(NOW.minus(Duration.ofDays(2)));
        when(skipCounts.snapshot()).thenReturn(Map.of(SKIP_LINT, 0L));

        SourceTierDtos.Evidence evidence = service(true, offered).evidence(30).block();

        assertThat(evidence.at()).isEqualTo(NOW);
        assertThat(evidence.days()).isEqualTo(30);
        assertThat(evidence.since()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(evidence.configVersion()).isEqualTo(7L);
        assertThat(evidence.skipsSince()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(relayReadSince).containsExactly(NOW.minus(Duration.ofDays(30)));
        assertThat(evidence.pairs()).as("pairs the release does not offer are not in the evidence").extracting(
                SourceTierDtos.PairEvidence::vendor, SourceTierDtos.PairEvidence::entryEndpoint, SourceTierDtos.PairEvidence::status)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("anthropic", "CHAT", "EXPERIMENTAL"),
                org.assertj.core.groups.Tuple.tuple("anthropic", "MESSAGES", "EXPERIMENTAL"),
                org.assertj.core.groups.Tuple.tuple("openai", "CHAT", "EXPERIMENTAL"),
                org.assertj.core.groups.Tuple.tuple("openai", "MESSAGES", "EXPERIMENTAL"));
        SourceTierDtos.PairEvidence messages = evidence.pairs().get(1);
        assertThat(messages.label()).isEqualTo("Anthropic");
        assertThat(messages.gate()).isEqualTo("KEYS");
        assertThat(messages.allowedKeys()).isEqualTo(1);
        assertThat(messages.figures()).isEqualTo(figures(250, 240, 4, 2, 10));
        assertThat(messages.lintSkips()).isZero();
        assertThat(messages.meetsStandard()).isTrue();
        assertThat(messages.criteria()).extracting(SourceTierDtos.Criterion::name, SourceTierDtos.Criterion::met)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("requests", true), org.assertj.core.groups.Tuple.tuple("lintFailures", true),
                org.assertj.core.groups.Tuple.tuple("conversionRejected", true), org.assertj.core.groups.Tuple.tuple("estimated", true),
                org.assertj.core.groups.Tuple.tuple("unbilled", true));
        assertThat(messages.degradedFeatures()).containsExactly(new SourceTierDtos.FeatureCount("image", 12));
        assertThat(messages.members()).as("the members that were sent this pair's requests, by tier and account").containsExactly(
            new SourceTierDtos.MemberEvidence("kiro", "relay-2", figures(1, 1, 0, 0, 0)),
            new SourceTierDtos.MemberEvidence("official", "relay", figures(250, 240, 4, 2, 10)));
        SourceTierDtos.PairEvidence chat = evidence.pairs().get(0);
        assertThat(chat.meetsStandard()).isFalse();
        assertThat(chat.criteria()).filteredOn(criterion -> !criterion.met()).extracting(SourceTierDtos.Criterion::name)
            .containsExactly("requests", "conversionRejected");
        SourceTierDtos.PairEvidence openai = evidence.pairs().get(2);
        assertThat(openai.figures()).isEqualTo(new SourceTierDtos.XpFigures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null));
        assertThat(openai.meetsStandard()).as("no attempts at all").isFalse();
        assertThat(openai.gate()).isEqualTo("OFF");
    }

    @Test
    void theLintFailuresOfAPairAreThoseOfItsVendorAndAnyOneMakesItFail() {
        loaded(withRelay(relay, mapping("claude-sonnet-4.5", true, true), gate("MESSAGES", "KEYS", "key-1")));
        pairFigures.add(new FigureRow(null, "anthropic", null, "MESSAGES", figures(300, 300, 0, 0, 0)));
        when(skipCounts.since()).thenReturn(NOW);
        when(skipCounts.snapshot()).thenReturn(Map.of(SKIP_LINT, 1L, new XpSkipCounts.Key("openai", "official", XpSkip.Reason.CONVERSION), 9L,
            new XpSkipCounts.Key("anthropic", "official", XpSkip.Reason.GATE), 50L));

        SourceTierDtos.PairEvidence messages = service(true, offered).evidence(7).block().pairs().get(1);

        assertThat(messages.lintSkips()).isEqualTo(1);
        assertThat(messages.criteria()).filteredOn(criterion -> !criterion.met()).extracting(SourceTierDtos.Criterion::name)
            .containsExactly("lintFailures");
        assertThat(messages.meetsStandard()).isFalse();
    }

    @Test
    void theEvidenceRefusesAWindowTheRequestLogCannotAnswerFor() {
        loaded(snapshot(true, anthropicTiers(), List.of()));

        for (int days : new int[] {0, -1, 91, 1000}) {
            assertThatThrownBy(() -> service(true, offered).evidence(days).block()).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_EVIDENCE_WINDOW_INVALID");
            });
        }
        assertThat(relayReadSince).isEmpty();
        assertThat(service(true, offered).evidence(1).block().days()).isEqualTo(1);
        assertThat(service(true, offered).evidence(90).block().days()).isEqualTo(90);
    }

    @Test
    void beforeTheConfigurationHasEverLoadedThereIsNoEvidenceEither() {
        SourceTierRegistry neverLoaded = new SourceTierRegistry(
            mock(DatabaseClient.class), mock(ReactiveTransactionManager.class), mock(SystemMaintenanceTaskGate.class));
        SourceTierRuntimeInfo runtime = new SourceTierRuntimeInfo(true, false, false, pair -> PairStatus.NOT_OFFERED);
        SourceTierStatusService service = new SourceTierStatusService(neverLoaded, runtime,
            new SourceTierValidator(catalog, keyResolver, runtime), accountPool, ranker, mock(DatabaseClient.class));

        assertThatThrownBy(() -> service.evidence(30).block()).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE"));
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
