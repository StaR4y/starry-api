package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.sourcetier.SourceTierAdminService;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierValidator;

/**
 * The administrator write against the real tables: the transaction, the lock, the versions, and that what this release
 * cannot run is not stored. The service of the application runs with the capabilities of this release (none); a second
 * one is built here with all of them, to exercise the paths a later release switches on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "starry.stats.aggregation-enabled=false"
})
class SourceTierAdminPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String VERSION_SETTING = "source_tier_config_version";

    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactionManager;
    @Autowired SystemSettingRepository settings;
    @Autowired SourceTierRegistry registry;
    @Autowired SourceTierAdminService thisRelease;
    @Autowired ModelCatalogProvider catalog;
    @Autowired ModelPoolKeyResolver resolver;

    private SourceTierAdminService capable;
    private final List<String> accounts = new ArrayList<>();

    @BeforeEach
    void startFromNothingConfigured() {
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = :key").bind("key", VERSION_SETTING)
            .fetch().rowsUpdated().block();
        registry.refresh().block();
        SourceTierRuntimeInfo everything = new SourceTierRuntimeInfo(true, true, true, pair -> PairStatus.VERIFIED);
        capable = new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, everything), everything);
    }

    // ---- fixtures ----

    private static SourceTierDtos.Tier tier(String id, int level, String name, String... categories) {
        return new SourceTierDtos.Tier(id, level, name, null, List.of(categories), null, null, null);
    }

    private static SourceTierDtos.Member pin(String accountId, String tierId) {
        return new SourceTierDtos.Member(accountId, tierId, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SourceTierDtos.Member relay(String accountId, String tierId, boolean vision, SourceTierDtos.MemberModel... models) {
        return new SourceTierDtos.Member(accountId, tierId, "OPENAI_CHAT", null, vision, null, null, null, null, null, null,
            false, true, List.of(models));
    }

    private static SourceTierDtos.MemberModel mapping(String modelId, String wire, int context) {
        return new SourceTierDtos.MemberModel(modelId, wire, context, null, null, null, null);
    }

    private static SourceTierDtos.ReplaceVendor replace(
        long expectedVersion, boolean enabled, List<SourceTierDtos.Tier> tiers, List<SourceTierDtos.Member> members,
        List<SourceTierDtos.Gate> gates
    ) {
        return new SourceTierDtos.ReplaceVendor(expectedVersion, enabled, null, null, null, null, null, tiers, members, gates);
    }

    private static final List<SourceTierDtos.Tier> OWNER_TIERS = List.of(
        tier("official", 1, "Official", "OFFICIAL_API_KEY"), tier("kiro", 2, "Kiro proxy", "KIRO", "CLAUDE_OAUTH"));

    private String account(String provider, String authMethod, String baseUrl, boolean enabled) {
        String id = "tt-" + UUID.randomUUID().toString().substring(0, 8);
        var insert = db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url, enabled)"
                + " VALUES (:id, 'token', :provider, :auth, :url, :enabled)")
            .bind("id", id).bind("provider", provider).bind("auth", authMethod).bind("enabled", enabled);
        (baseUrl == null ? insert.bindNull("url", String.class) : insert.bind("url", baseUrl)).fetch().rowsUpdated().block();
        accounts.add(id);
        return id;
    }

    private String relayAccount() {
        return account("OpenAI-compatible", "api-key", "https://relay.example/v1", true);
    }

    @AfterEach
    void removeAccounts() {
        for (String id : accounts) {
            db.sql("DELETE FROM proxy_accounts WHERE account_id = :id").bind("id", id).fetch().rowsUpdated().block();
        }
        accounts.clear();
    }

    private long configVersion() {
        return Long.parseLong(db.sql("SELECT setting_value FROM system_settings WHERE setting_key = :key").bind("key", VERSION_SETTING)
            .map((row, metadata) -> row.get("setting_value", String.class)).one().block());
    }

    private long count(String sql) {
        return db.sql(sql).map((row, metadata) -> row.get(0, Number.class).longValue()).one().block();
    }

    private static ApiException rejection(Mono<?> write) {
        try {
            write.block();
        } catch (ApiException expected) {
            return expected;
        }
        throw new AssertionError("expected a rejection");
    }

    // ---- the write ----

    @Test
    void aVendorIsStoredAsSentAndTheVersionsAdvance() {
        String kiroAccount = account("BuilderId", "idc", null, true);

        SourceTierDtos.Vendor saved = thisRelease.replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, false, null, true, false, 24,
            false, OWNER_TIERS, List.of(pin(kiroAccount, "official")),
            List.of(new SourceTierDtos.Gate(null, "MESSAGES", "OFF", null)))).block();

        assertThat(saved.configured()).isTrue();
        assertThat(saved.version()).isEqualTo(1L);
        assertThat(saved.label()).isEqualTo("Anthropic");
        assertThat(saved.enabled()).isFalse();
        assertThat(saved.publicListing()).isTrue();
        assertThat(saved.stickySessions()).isFalse();
        assertThat(saved.refusalCap()).isEqualTo(24);
        assertThat(saved.crossTierReservedSlot()).isFalse();
        assertThat(saved.tiers()).extracting(SourceTierDtos.Tier::tierId).containsExactly("official", "kiro");
        assertThat(saved.tiers().getFirst().priceFactor()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(saved.tiers().get(1).memberCategories()).containsExactly("CLAUDE_OAUTH", "KIRO");
        assertThat(saved.tiers().get(1).userSelectable()).isTrue();
        assertThat(saved.members()).singleElement().satisfies(member -> {
            assertThat(member.accountId()).isEqualTo(kiroAccount);
            assertThat(member.tierId()).isEqualTo("official");
            assertThat(member.protocol()).isNull();
            assertThat(member.models()).isNull();
        });
        assertThat(saved.gates()).singleElement().satisfies(gate -> {
            assertThat(gate.sourceProtocol()).isEqualTo("OPENAI_CHAT");
            assertThat(gate.entryEndpoint()).isEqualTo("MESSAGES");
            assertThat(gate.state()).isEqualTo("OFF");
        });
        assertThat(configVersion()).isEqualTo(1L);
        // This node reads the change without waiting for the NOTIFY.
        assertThat(registry.snapshot().configVersion()).isEqualTo(1L);
        assertThat(registry.snapshot().tiers("anthropic")).extracting(SourceTierSnapshot.Tier::tierId).containsExactly("official", "kiro");
        assertThat(registry.snapshot().member("anthropic", kiroAccount)).isPresent();

        SourceTierDtos.Vendor second = thisRelease.replace("anthropic", replace(1L, false, List.of(tier("only", 1, "Only")), List.of(), List.of()))
            .block();

        assertThat(second.version()).isEqualTo(2L);
        assertThat(second.tiers()).extracting(SourceTierDtos.Tier::tierId).containsExactly("only");
        assertThat(second.members()).isEmpty();
        assertThat(second.gates()).isEmpty();
        assertThat(second.refusalCap()).as("what is not sent goes back to the default").isEqualTo(16);
        assertThat(configVersion()).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM source_tiers WHERE vendor = 'anthropic'")).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM source_tier_members WHERE vendor = 'anthropic'")).isZero();
        assertThat(count("SELECT count(*) FROM source_tier_pair_gates WHERE vendor = 'anthropic'")).isZero();
    }

    @Test
    void vendorsAreIndependentButShareTheCounterTheNodesWatch() {
        thisRelease.replace("anthropic", replace(0L, false, List.of(tier("a", 1, "A")), List.of(), List.of())).block();
        SourceTierDtos.Vendor openai = thisRelease.replace("openai", replace(0L, false, List.of(tier("o", 1, "O")), List.of(), List.of())).block();

        assertThat(openai.version()).isEqualTo(1L);
        assertThat(configVersion()).isEqualTo(2L);
        SourceTierDtos.Overview overview = thisRelease.overview().block();
        assertThat(overview.configVersion()).isEqualTo(2L);
        assertThat(overview.vendors().stream().filter(vendor -> vendor.vendor().equals("anthropic")).findFirst().orElseThrow().version())
            .isEqualTo(1L);
    }

    @Test
    void aCounterThatIsNotANumberStartsOverAtOne() {
        db.sql("UPDATE system_settings SET setting_value = 'junk' WHERE setting_key = :key").bind("key", VERSION_SETTING)
            .fetch().rowsUpdated().block();

        thisRelease.replace("anthropic", replace(0L, false, List.of(tier("a", 1, "A")), List.of(), List.of())).block();

        assertThat(configVersion()).isEqualTo(1L);
    }

    // ---- what is refused ----

    @Test
    void nothingThisReleaseCannotRunIsStored() {
        String relay = relayAccount();
        List<SourceTierDtos.ReplaceVendor> unsupported = List.of(
            new SourceTierDtos.ReplaceVendor(0L, false, true, null, null, null, null, OWNER_TIERS, List.of(), List.of()),
            replace(0L, false, List.of(tier("relay", 1, "Relay")), List.of(relay(relay, "relay", false,
                mapping("claude-sonnet-4.5", "relay/sonnet", 200_000))), List.of()),
            replace(0L, false, OWNER_TIERS, List.of(), List.of(new SourceTierDtos.Gate(null, "MESSAGES", "KEYS", List.of("key-1")))),
            replace(0L, false, OWNER_TIERS, List.of(), List.of(new SourceTierDtos.Gate(null, "CHAT", "ON", null))));

        for (SourceTierDtos.ReplaceVendor request : unsupported) {
            ApiException refused = rejection(thisRelease.replace("anthropic", request));
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_RUNTIME_UNSUPPORTED");
        }

        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isZero();
        assertThat(count("SELECT count(*) FROM source_tiers")).isZero();
        assertThat(count("SELECT count(*) FROM source_tier_members")).isZero();
        assertThat(count("SELECT count(*) FROM source_tier_pair_gates")).isZero();
        assertThat(configVersion()).as("a refused change is not a change").isZero();
    }

    @Test
    void aVendorCanBeEnabledBecauseTheReleaseTiersNatively() {
        SourceTierDtos.Vendor enabled = thisRelease.replace("anthropic", replace(0L, true, OWNER_TIERS, List.of(), List.of())).block();

        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.tiers()).extracting(SourceTierDtos.Tier::tierId).containsExactly("official", "kiro");
        assertThat(count("SELECT count(*) FROM source_tier_vendors WHERE enabled")).isEqualTo(1L);
    }

    @Test
    void theSameConfigurationWithTheVendorOffIsStoredSoItCanBePrepared() {
        SourceTierDtos.Vendor prepared = thisRelease.replace("anthropic", replace(0L, false, OWNER_TIERS, List.of(),
            List.of(new SourceTierDtos.Gate(null, "MESSAGES", "OFF", null)))).block();

        assertThat(prepared.enabled()).isFalse();
        assertThat(prepared.tiers()).hasSize(2);
    }

    @Test
    void aStaleVersionIsAConflictThatStoresNothing() {
        ApiException ahead = rejection(thisRelease.replace("anthropic", replace(5L, false, OWNER_TIERS, List.of(), List.of())));
        assertThat(ahead.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ahead.getCode()).isEqualTo("SOURCE_TIER_VERSION_CONFLICT");
        assertThat(ahead.getDetails()).hasToString("{currentVersion=0}");
        assertThat(count("SELECT count(*) FROM source_tiers")).isZero();

        thisRelease.replace("anthropic", replace(0L, false, OWNER_TIERS, List.of(), List.of())).block();
        ApiException behind = rejection(thisRelease.replace("anthropic", replace(0L, false, List.of(tier("x", 1, "X")), List.of(), List.of())));
        assertThat(behind.getCode()).isEqualTo("SOURCE_TIER_VERSION_CONFLICT");
        assertThat(behind.getDetails()).hasToString("{currentVersion=1}");
        assertThat(thisRelease.overview().block().vendors().getFirst().tiers()).extracting(SourceTierDtos.Tier::tierId)
            .containsExactly("official", "kiro");
        assertThat(configVersion()).isEqualTo(1L);
    }

    @Test
    void concurrentWritesFromTheSameVersionHaveOneWinner() {
        int writers = 4;
        List<Object> outcomes = Flux.range(0, writers)
            .flatMap(index -> thisRelease.replace("anthropic", replace(0L, false, List.of(tier("writer-" + index, 1, "Writer " + index)),
                    List.of(), List.of()))
                .map(vendor -> (Object) vendor)
                .onErrorResume(error -> Mono.just((Object) error))
                .subscribeOn(Schedulers.boundedElastic()), writers)
            .collectList().block();

        assertThat(outcomes).hasSize(writers);
        assertThat(outcomes.stream().filter(SourceTierDtos.Vendor.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(ApiException.class::isInstance).map(ApiException.class::cast))
            .hasSize(writers - 1).allSatisfy(error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_VERSION_CONFLICT");
            });
        SourceTierDtos.Vendor winner = (SourceTierDtos.Vendor) outcomes.stream().filter(SourceTierDtos.Vendor.class::isInstance).findFirst().orElseThrow();
        assertThat(winner.version()).isEqualTo(1L);
        assertThat(configVersion()).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM source_tiers WHERE vendor = 'anthropic'")).isEqualTo(1L);
        assertThat(thisRelease.overview().block().vendors().getFirst().tiers()).extracting(SourceTierDtos.Tier::tierId)
            .containsExactly(winner.tiers().getFirst().tierId());
    }

    @Test
    void concurrentWritesToOtherVendorsAllLandAndCountOnce() {
        List<String> vendors = List.of("anthropic", "openai", "google", "deepseek");
        List<Object> outcomes = Flux.fromIterable(vendors)
            .flatMap(vendor -> thisRelease.replace(vendor, replace(0L, false, List.of(tier("t", 1, "T")), List.of(), List.of()))
                .map(saved -> (Object) saved)
                .onErrorResume(error -> Mono.just((Object) error))
                .subscribeOn(Schedulers.boundedElastic()), vendors.size())
            .collectList().block();

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(SourceTierDtos.Vendor.class));
        assertThat(configVersion()).as("one bump per committed write, none lost").isEqualTo(vendors.size());
        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isEqualTo(vendors.size());
    }

    @Test
    void aWriteWaitsForAnOpenTransactionOfAnotherWriter() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Another writer, holding the lock the way a write does.
        Mono<Void> holder = org.springframework.transaction.reactive.TransactionalOperator.create(transactionManager).transactional(
            settings.acquireTransactionLock(SourceTierAdminService.LOCK).doOnNext(ignored -> holding.countDown())
                .then(Mono.fromRunnable(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).subscribeOn(Schedulers.boundedElastic())).then());
        holder.subscribeOn(Schedulers.boundedElastic()).subscribe();
        assertThat(holding.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        var write = thisRelease.replace("anthropic", replace(0L, false, List.of(tier("a", 1, "A")), List.of(), List.of()))
            .subscribeOn(Schedulers.boundedElastic()).toFuture();
        Thread.sleep(800);
        assertThat(write).as("the write is waiting for the lock").isNotDone();
        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isZero();

        release.countDown();
        assertThat(write.get(20, java.util.concurrent.TimeUnit.SECONDS).version()).isEqualTo(1L);
    }

    @Test
    void aWriteThatFailsAfterItsRowsAreWrittenLeavesNothingBehind() {
        String relay = relayAccount();
        String kiro = account("BuilderId", "idc", null, true);
        // The last statement of a write bumps the counter. Without the right to do that, all that came before must go.
        executeAsDatabaseOwner("REVOKE UPDATE ON TABLE starry_api.system_settings FROM starry_api_runtime");
        try {
            Mono<SourceTierDtos.Vendor> write = capable.replace("anthropic", replace(0L, true,
                List.of(tier("relay", 1, "Relay"), tier("kiro", 2, "Kiro proxy", "KIRO")),
                List.of(pin(kiro, "kiro"), relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet", 200_000))),
                List.of(new SourceTierDtos.Gate(null, "CHAT", "KEYS", List.of("key-1")))));
            assertThatThrownBy(write::block).isNotInstanceOf(ApiException.class).hasMessageContaining("system_settings");
        } finally {
            executeAsDatabaseOwner("GRANT UPDATE ON TABLE starry_api.system_settings TO starry_api_runtime");
        }

        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isZero();
        assertThat(count("SELECT count(*) FROM source_tiers")).isZero();
        assertThat(count("SELECT count(*) FROM source_tier_members")).isZero();
        assertThat(count("SELECT count(*) FROM source_tier_member_models")).isZero();
        assertThat(count("SELECT count(*) FROM source_tier_pair_gates")).isZero();
        assertThat(configVersion()).isZero();
    }

    @Test
    void aVendorTheCatalogDoesNotKnowIsRefusedUnlessItWasConfiguredBefore() {
        ApiException unknown = rejection(thisRelease.replace("no-such-vendor", replace(0L, false, List.of(), List.of(), List.of())));
        assertThat(unknown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getCode()).isEqualTo("SOURCE_TIER_VENDOR_UNKNOWN");
        assertThat(rejection(thisRelease.replace("Bad Vendor", replace(0L, false, List.of(), List.of(), List.of()))).getCode())
            .isEqualTo("SOURCE_TIER_VENDOR_UNKNOWN");

        // A vendor that has left the catalog since it was configured can still be cleaned up.
        db.sql("INSERT INTO source_tier_vendors(vendor) VALUES ('retired-vendor')").fetch().rowsUpdated().block();
        assertThat(thisRelease.overview().block().vendors()).extracting(SourceTierDtos.Vendor::vendor).contains("retired-vendor");
        SourceTierDtos.Vendor cleaned = thisRelease.replace("retired-vendor", replace(1L, false, List.of(), List.of(), List.of())).block();
        assertThat(cleaned.version()).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isEqualTo(1L);
    }

    @Test
    void anAccountThatDoesNotExistIsRefusedAndStoresNothing() {
        ApiException refused = rejection(thisRelease.replace("anthropic", replace(0L, false, OWNER_TIERS, List.of(pin("tt-missing", "kiro")), List.of())));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_MEMBER_INVALID");
        assertThat(refused.getMessage()).contains("tt-missing");
        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isZero();
        assertThat(configVersion()).isZero();
    }

    // ---- reading and warnings ----

    @Test
    void theOverviewListsTheCatalogVendorsTheCapabilitiesAndThePairs() {
        SourceTierDtos.Overview overview = thisRelease.overview().block();

        assertThat(overview.configVersion()).isZero();
        // Native tiering is complete in this release; what the later phases bring is still off.
        assertThat(overview.runtime().nativeTiers()).isTrue();
        assertThat(overview.runtime().selectors()).isFalse();
        assertThat(overview.runtime().unifyEndpoints()).isFalse();
        assertThat(overview.runtime().xpMembers()).isFalse();
        assertThat(overview.runtime().pairs()).hasSize(12).allSatisfy(pair -> assertThat(pair.status()).isEqualTo("NOT_OFFERED"));
        assertThat(overview.vendors()).extracting(SourceTierDtos.Vendor::vendor).startsWith("anthropic", "openai", "google", "deepseek");
        assertThat(overview.vendors()).allSatisfy(vendor -> {
            assertThat(vendor.configured()).isFalse();
            assertThat(vendor.version()).isZero();
            assertThat(vendor.enabled()).isFalse();
            assertThat(vendor.stickySessions()).isTrue();
            assertThat(vendor.refusalCap()).isEqualTo(16);
            assertThat(vendor.crossTierReservedSlot()).isTrue();
            assertThat(vendor.tiers()).isEmpty();
            assertThat(vendor.warnings()).isEmpty();
            assertThat(vendor.tierAccounts()).isEmpty();
        });
    }

    @Test
    void theOverviewWarnsWhenTheLowestTierCollectsOfficialKeysAndOfAPinThatCannotServe() {
        String official = account("Anthropic", "api-key", null, true);
        String disabled = account("Anthropic", "api-key", null, false);
        String openAi = account("OpenAI", "api-key", null, true);
        String kiro = account("BuilderId", "idc", null, true);

        SourceTierDtos.Vendor saved = thisRelease.replace("anthropic", replace(0L, false,
            List.of(tier("relay", 1, "Relay"), tier("kiro", 2, "Kiro proxy", "KIRO")),
            List.of(pin(openAi, "kiro"), pin(kiro, "relay")), List.of())).block();

        assertThat(saved.warnings()).extracting(SourceTierDtos.Warning::code)
            .containsExactly("LOWEST_TIER_COLLECTS_OFFICIAL_KEYS", "MEMBER_NOT_SERVING_VENDOR");
        SourceTierDtos.Warning collected = saved.warnings().getFirst();
        assertThat(collected.tierId()).isEqualTo("kiro");
        assertThat(collected.accountIds()).contains(official).doesNotContain(disabled, openAi, kiro);
        assertThat(saved.warnings().get(1).accountIds()).containsExactly(openAi);
        // The unpinned official key falls into the lowest tier, the pinned Kiro account sits where it is pinned, and the
        // pinned OpenAI key has no route to Claude models, so it fills no tier.
        assertThat(saved.tierAccounts()).containsEntry("kiro", 1).containsEntry("relay", 1);

        // Listing the category in a tier removes the first warning.
        SourceTierDtos.Vendor fixed = thisRelease.replace("anthropic", replace(1L, false,
            List.of(tier("relay", 1, "Relay", "OFFICIAL_API_KEY"), tier("kiro", 2, "Kiro proxy", "KIRO")),
            List.of(pin(openAi, "kiro"), pin(kiro, "relay")), List.of())).block();
        assertThat(fixed.warnings()).extracting(SourceTierDtos.Warning::code).containsExactly("MEMBER_NOT_SERVING_VENDOR");
    }

    // ---- relay members ----

    @Test
    void aRelayIsStoredWithItsModelsAndVerificationFollowsTheFingerprint() {
        String relay = relayAccount();
        List<SourceTierDtos.Tier> tiers = List.of(tier("relay", 1, "Relay"), tier("kiro", 2, "Kiro proxy", "KIRO"));
        SourceTierDtos.Member row = relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet", 200_000),
            mapping("claude-haiku-4.5", "relay/haiku", 100_000));

        SourceTierDtos.Vendor saved = capable.replace("anthropic", replace(0L, true, tiers, List.of(row), List.of())).block();

        assertThat(saved.enabled()).isTrue();
        SourceTierDtos.Member stored = saved.members().getFirst();
        assertThat(stored.protocol()).isEqualTo("OPENAI_CHAT");
        assertThat(stored.exclusive()).isTrue();
        assertThat(stored.upstreamStream()).isTrue();
        assertThat(stored.maxDurationSeconds()).isEqualTo(540);
        assertThat(stored.usageCacheReadInPrompt()).isFalse();
        assertThat(stored.usageCacheWriteInPrompt()).isTrue();
        assertThat(stored.models()).extracting(SourceTierDtos.MemberModel::modelId).containsExactly("claude-haiku-4.5", "claude-sonnet-4.5");
        assertThat(stored.models()).allSatisfy(model -> {
            assertThat(model.verifiedAt()).isNull();
            assertThat(model.verified()).isFalse();
            assertThat(model.imageTokenCap()).isEqualTo(8192);
        });
        assertThat(registry.snapshot().exclusiveXpAccountIds()).containsExactly(relay);

        // A probe stores what it vouched for; the fingerprint is the one of the configuration as stored.
        verify(relay, "claude-sonnet-4.5", "{\"ok\":true}");
        assertThat(model(capable.overview().block(), relay, "claude-sonnet-4.5").verified()).isTrue();
        assertThat(model(capable.overview().block(), relay, "claude-haiku-4.5").verified()).isFalse();

        // Saving the same mapping again keeps the verification, summary included; limits and tier do not count.
        SourceTierDtos.Member resaved = new SourceTierDtos.Member(relay, "kiro", "OPENAI_CHAT", false, false, null, null, null, null, null, 300,
            false, true, List.of(mapping("claude-sonnet-4.5", "relay/sonnet", 50_000)));
        capable.replace("anthropic", replace(1L, true, tiers, List.of(resaved), List.of())).block();
        SourceTierDtos.MemberModel kept = model(capable.overview().block(), relay, "claude-sonnet-4.5");
        assertThat(kept.verified()).isTrue();
        assertThat(kept.maxContextTokens()).isEqualTo(50_000);
        assertThat(summary(relay, "claude-sonnet-4.5")).contains("\"ok\"").contains("true");

        // A setting that changes what the verification vouches for clears it.
        SourceTierDtos.Member withVision = relay(relay, "kiro", true, mapping("claude-sonnet-4.5", "relay/sonnet", 50_000));
        capable.replace("anthropic", replace(2L, true, tiers, List.of(withVision), List.of())).block();
        SourceTierDtos.MemberModel cleared = model(capable.overview().block(), relay, "claude-sonnet-4.5");
        assertThat(cleared.verified()).isFalse();
        assertThat(cleared.verifiedAt()).isNull();
        assertThat(summary(relay, "claude-sonnet-4.5")).isEqualTo("{}");
        assertThat(verifiedHash(relay, "claude-sonnet-4.5")).isNull();
    }

    @Test
    void renamingTheRelaysModelOrMovingTheAccountClearsTheVerificationToo() {
        String relay = relayAccount();
        List<SourceTierDtos.Tier> tiers = List.of(tier("relay", 1, "Relay"));
        capable.replace("anthropic", replace(0L, false, tiers,
            List.of(relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet", 200_000))), List.of())).block();
        verify(relay, "claude-sonnet-4.5", "{\"ok\":true}");

        // The account now points somewhere else: the stored verification no longer matches, even before any save.
        db.sql("UPDATE proxy_accounts SET base_url = 'https://other-relay.example/v1' WHERE account_id = :id").bind("id", relay)
            .fetch().rowsUpdated().block();
        assertThat(model(capable.overview().block(), relay, "claude-sonnet-4.5").verified()).isFalse();
        capable.replace("anthropic", replace(1L, false, tiers,
            List.of(relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet", 200_000))), List.of())).block();
        assertThat(verifiedHash(relay, "claude-sonnet-4.5")).as("not carried over to a different base URL").isNull();

        verify(relay, "claude-sonnet-4.5", "{\"ok\":true}");
        capable.replace("anthropic", replace(2L, false, tiers,
            List.of(relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet-v2", 200_000))), List.of())).block();
        assertThat(verifiedHash(relay, "claude-sonnet-4.5")).as("another name on the relay").isNull();
    }

    @Test
    void aMappingThatIsRemovedAndAddedBackStartsUnverified() {
        String relay = relayAccount();
        List<SourceTierDtos.Tier> tiers = List.of(tier("relay", 1, "Relay"));
        SourceTierDtos.Member row = relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet", 200_000));
        capable.replace("anthropic", replace(0L, false, tiers, List.of(row), List.of())).block();
        verify(relay, "claude-sonnet-4.5", "{\"ok\":true}");

        capable.replace("anthropic", replace(1L, false, tiers, List.of(), List.of())).block();
        capable.replace("anthropic", replace(2L, false, tiers, List.of(row), List.of())).block();

        assertThat(verifiedHash(relay, "claude-sonnet-4.5")).isNull();
    }

    @Test
    void aRelayThatIsNotAnOpenAiCompatibleKeyIsRefused() {
        String official = account("OpenAI", "api-key", null, true);

        ApiException refused = rejection(capable.replace("anthropic", replace(0L, false, List.of(tier("relay", 1, "Relay")),
            List.of(relay(official, "relay", false, mapping("claude-sonnet-4.5", "x", 1000))), List.of())));

        assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_MEMBER_INVALID");
        assertThat(refused.getMessage()).contains("not an OpenAI-compatible API key");
        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isZero();
    }

    // ---- member changes ----

    @Test
    void membersAreAddedUpdatedAndRemovedWithoutTheRestOfTheConfiguration() {
        String kiro1 = account("BuilderId", "idc", null, true);
        String kiro2 = account("BuilderId", "idc", null, true);
        String relay = relayAccount();
        List<SourceTierDtos.Tier> tiers = List.of(tier("relay", 1, "Relay"), tier("kiro", 2, "Kiro proxy", "KIRO"));
        capable.replace("anthropic", replace(0L, false, tiers, List.of(pin(kiro1, "kiro"),
            relay(relay, "relay", false, mapping("claude-sonnet-4.5", "relay/sonnet", 200_000))),
            List.of(new SourceTierDtos.Gate(null, "CHAT", "KEYS", List.of("key-1"))))).block();

        SourceTierDtos.Vendor changed = capable.upsertMembers("anthropic", new SourceTierDtos.UpsertMembers(1L,
            List.of(pin(kiro2, "relay"), relay(relay, "relay", true, mapping("claude-opus-4.5", "relay/opus", 100_000))),
            List.of(kiro1))).block();

        assertThat(changed.version()).isEqualTo(2L);
        assertThat(changed.members()).extracting(SourceTierDtos.Member::accountId).containsExactlyInAnyOrder(kiro2, relay);
        SourceTierDtos.Member updated = changed.members().stream().filter(member -> member.accountId().equals(relay)).findFirst().orElseThrow();
        assertThat(updated.vision()).isTrue();
        assertThat(updated.models()).extracting(SourceTierDtos.MemberModel::modelId).containsExactly("claude-opus-4.5");
        assertThat(changed.tiers()).as("the rest of the configuration is untouched").extracting(SourceTierDtos.Tier::tierId)
            .containsExactly("relay", "kiro");
        assertThat(changed.gates()).extracting(SourceTierDtos.Gate::state).containsExactly("KEYS");
        assertThat(configVersion()).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM source_tier_member_models WHERE account_id = '" + relay + "'")).isEqualTo(1L);

        ApiException stale = rejection(capable.upsertMembers("anthropic", new SourceTierDtos.UpsertMembers(1L, List.of(pin(kiro1, "kiro")), null)));
        assertThat(stale.getCode()).isEqualTo("SOURCE_TIER_VERSION_CONFLICT");
        ApiException toNowhere = rejection(capable.upsertMembers("anthropic", new SourceTierDtos.UpsertMembers(2L, List.of(pin(kiro1, "gone")), null)));
        assertThat(toNowhere.getCode()).isEqualTo("SOURCE_TIER_MEMBER_INVALID");
        assertThat(configVersion()).isEqualTo(2L);
    }

    @Test
    void membersOfAVendorThatWasNeverConfiguredHaveNoTierToGoTo() {
        String kiro = account("BuilderId", "idc", null, true);

        ApiException refused = rejection(thisRelease.upsertMembers("anthropic",
            new SourceTierDtos.UpsertMembers(0L, List.of(pin(kiro, "kiro")), null)));

        assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_MEMBER_INVALID");
        assertThat(count("SELECT count(*) FROM source_tier_vendors")).isZero();
    }

    @Test
    void thisReleaseRefusesARelayRowEvenThroughTheMemberEndpoint() {
        String relay = relayAccount();
        thisRelease.replace("anthropic", replace(0L, false, List.of(tier("relay", 1, "Relay")), List.of(), List.of())).block();

        ApiException refused = rejection(thisRelease.upsertMembers("anthropic", new SourceTierDtos.UpsertMembers(1L,
            List.of(relay(relay, "relay", false, mapping("claude-sonnet-4.5", "x", 1000))), null)));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_RUNTIME_UNSUPPORTED");
        assertThat(count("SELECT count(*) FROM source_tier_members")).isZero();
        assertThat(configVersion()).isEqualTo(1L);
    }

    @Test
    void aNativePinGoesThroughTheMemberEndpointWhileThisReleaseStillCannotEnable() {
        String kiro = account("BuilderId", "idc", null, true);
        thisRelease.replace("anthropic", replace(0L, false, OWNER_TIERS, List.of(), List.of())).block();

        SourceTierDtos.Vendor changed = thisRelease.upsertMembers("anthropic",
            new SourceTierDtos.UpsertMembers(1L, List.of(pin(kiro, "official")), null)).block();

        assertThat(changed.members()).singleElement().satisfies(member -> assertThat(member.tierId()).isEqualTo("official"));
        assertThat(changed.enabled()).isFalse();
    }

    // ---- helpers ----

    /** What the verifier of a later task does: stores the fingerprint of the stored mapping and its findings. */
    private void verify(String accountId, String modelId, String summary) {
        SourceTierSnapshot snapshot = registry.snapshot();
        SourceTierSnapshot.Member member = snapshot.member("anthropic", accountId).orElseThrow();
        SourceTierSnapshot.MemberModel model = snapshot.models("anthropic", accountId).stream()
            .filter(candidate -> candidate.modelId().equals(modelId)).findFirst().orElseThrow();
        ProxyAccountEntity account = new ProxyAccountEntity();
        db.sql("SELECT provider, base_url FROM proxy_accounts WHERE account_id = :id").bind("id", accountId)
            .map((row, metadata) -> {
                account.setProvider(row.get("provider", String.class));
                account.setBaseUrl(row.get("base_url", String.class));
                return account;
            }).one().block();
        db.sql("""
                UPDATE source_tier_member_models
                SET verified_at = :at, verified_hash = :hash, verify_summary = CAST(:summary AS JSONB)
                WHERE vendor = 'anthropic' AND account_id = :id AND model_id = :model
                """)
            .bind("at", Instant.parse("2026-10-25T01:00:00Z")).bind("hash", SourceTierValidator.mappingFingerprint(member, model, account))
            .bind("summary", summary).bind("id", accountId).bind("model", modelId).fetch().rowsUpdated().block();
        // The probe's write is a change like any other: this node reads it again.
        registry.refresh().block();
    }

    private static SourceTierDtos.MemberModel model(SourceTierDtos.Overview overview, String accountId, String modelId) {
        return overview.vendors().stream().filter(vendor -> vendor.vendor().equals("anthropic")).findFirst().orElseThrow()
            .members().stream().filter(member -> member.accountId().equals(accountId)).findFirst().orElseThrow()
            .models().stream().filter(model -> model.modelId().equals(modelId)).findFirst().orElseThrow();
    }

    private String summary(String accountId, String modelId) {
        return db.sql("SELECT CAST(verify_summary AS TEXT) AS summary FROM source_tier_member_models WHERE account_id = :id AND model_id = :model")
            .bind("id", accountId).bind("model", modelId).map((row, metadata) -> row.get("summary", String.class)).one().block();
    }

    private String verifiedHash(String accountId, String modelId) {
        return db.sql("SELECT verified_hash FROM source_tier_member_models WHERE account_id = :id AND model_id = :model")
            .bind("id", accountId).bind("model", modelId)
            .map((row, metadata) -> java.util.Optional.ofNullable(row.get("verified_hash", String.class)))
            .one().block().orElse(null);
    }
}
