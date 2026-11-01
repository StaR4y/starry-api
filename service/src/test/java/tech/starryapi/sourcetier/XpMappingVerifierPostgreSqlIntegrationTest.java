package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountTestService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.ProxyAccountRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.openai.OpenAiRelayModelsClient;
import tech.starryapi.postgresql.PostgreSqlIntegrationTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The verification of a relay (design 7.4) end to end: the probes against a relay on the loopback address, the result in
 * the real tables, and what the rest of the system makes of it. That a verified mapping is the one whose stored hash is
 * the fingerprint of what is stored; that a failed or stale run never leaves a verification behind; that the evidence of
 * the cache probe is what lets a member be saved with "not contained", survives that save and is gone when the relay
 * moves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "starry.stats.aggregation-enabled=false"
})
class XpMappingVerifierPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION_SETTING = "source_tier_config_version";
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired DatabaseClient db;
    @Autowired SourceTierAdminService admin;
    @Autowired SourceTierRegistry registry;
    @Autowired ProxyAccountRepository accounts;
    @Autowired OpenAiChatRelayClient chatClient;
    @Autowired OpenAiRelayModelsClient modelsClient;
    @Autowired ModelCatalogProvider catalog;
    @Autowired XpMappingVerifier wired;
    @Autowired SourceTierAdminController controller;
    @Autowired AccountTestService accountTests;

    private final List<String> accountIds = new ArrayList<>();
    private final List<RelaySimulator> relays = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private XpMappingVerifier verifier;
    private RelaySimulator relay;
    private String accountId;

    @BeforeEach
    void aRelayAndAnAccountThatUsesIt() throws Exception {
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = :key").bind("key", VERSION_SETTING)
            .fetch().rowsUpdated().block();
        registry.refresh().block();
        relay = new RelaySimulator();
        relays.add(relay);
        accountId = "tt-" + UUID.randomUUID().toString().substring(0, 8);
        db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url, enabled)"
                + " VALUES (:id, 'sk-relay', 'OpenAI-compatible', 'api-key', :url, TRUE)")
            .bind("id", accountId).bind("url", relay.url()).fetch().rowsUpdated().block();
        accountIds.add(accountId);
        verifier = new XpMappingVerifier(admin, accounts, chatClient, modelsClient, catalog, clock, Duration.ZERO);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (RelaySimulator simulator : relays) {
            simulator.close();
        }
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        for (String id : accountIds) {
            db.sql("DELETE FROM proxy_accounts WHERE account_id = :id").bind("id", id).fetch().rowsUpdated().block();
        }
    }

    // ---- fixtures ----

    private static SourceTierDtos.MemberModel mapping(String modelId, String wire) {
        return new SourceTierDtos.MemberModel(modelId, wire, 200_000, null, null, null, null);
    }

    private SourceTierDtos.Member relayRow(boolean readInPrompt, boolean writeInPrompt, SourceTierDtos.MemberModel... models) {
        return new SourceTierDtos.Member(accountId, "relay", "OPENAI_CHAT", null, false, null, null, null, true, null, null,
            readInPrompt, writeInPrompt, List.of(models));
    }

    private SourceTierDtos.Vendor save(long version, SourceTierDtos.Member row) {
        return admin.replace("anthropic", new SourceTierDtos.ReplaceVendor(version, false, null, null, null, null, null,
            List.of(new SourceTierDtos.Tier("relay", 1, "Relay", null, List.of(), null, null, null)), List.of(row), List.of())).block();
    }

    private ApiException refusedSave(long version, SourceTierDtos.Member row) {
        try {
            save(version, row);
        } catch (ApiException expected) {
            return expected;
        }
        throw new AssertionError("expected a refusal");
    }

    private SourceTierDtos.VerifyResult verify(boolean cacheProbe, String... modelIds) {
        clock.advance(Duration.ofSeconds(31));
        return verifier.verify("anthropic", accountId, new SourceTierDtos.VerifyMember(modelIds.length == 0 ? null : List.of(modelIds), cacheProbe))
            .block(WAIT);
    }

    private long configVersion() {
        return Long.parseLong(db.sql("SELECT setting_value FROM system_settings WHERE setting_key = :key").bind("key", VERSION_SETTING)
            .map((row, metadata) -> row.get("setting_value", String.class)).one().block());
    }

    private long vendorVersion() {
        return db.sql("SELECT version FROM source_tier_vendors WHERE vendor = 'anthropic'")
            .map((row, metadata) -> row.get("version", Number.class).longValue()).one().block();
    }

    /** verified_at, verified_hash and the summary of a mapping, as stored. */
    private record Stored(Instant verifiedAt, String hash, JsonNode summary) {
    }

    private Stored stored(String modelId) {
        return db.sql("SELECT verified_at, verified_hash, CAST(verify_summary AS TEXT) AS summary FROM source_tier_member_models"
                + " WHERE account_id = :id AND model_id = :model")
            .bind("id", accountId).bind("model", modelId)
            .map((row, metadata) -> new Stored(row.get("verified_at", Instant.class), row.get("verified_hash", String.class),
                JSON.readTree(row.get("summary", String.class))))
            .one().block();
    }

    private boolean verifiedInTheRegistry(String modelId) {
        SourceTierSnapshot snapshot = registry.snapshot();
        SourceTierSnapshot.Member member = snapshot.member("anthropic", accountId).orElseThrow();
        SourceTierSnapshot.MemberModel model = snapshot.models("anthropic", accountId).stream()
            .filter(candidate -> candidate.modelId().equals(modelId)).findFirst().orElseThrow();
        ProxyAccountEntity account = accounts.findByAccountId(accountId).block();
        return SourceTierValidator.isVerified(member, model, account);
    }

    // ---- the application ----

    @Test
    void theVerifierTheEndpointsAndTheAccountTestsAreWiredIntoTheApplication() {
        assertThat(wired).isNotNull();
        assertThat(controller).isNotNull();
        assertThat(accountTests).isNotNull();
        // The wiring is the one the application runs with: its own verifier verifies a relay too.
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(wired.verify("anthropic", accountId, null).block(WAIT).verified()).isTrue();
    }

    @Test
    void anAccountTestOfARelayAsksItForTheNameItServesTheFirstMappingUnder() {
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "Relay/Claude-Sonnet-4.5")));

        AccountDtos.TestAccountsResponse response = accountTests
            .test(new AccountDtos.TestAccountsRequest(List.of(accountId), true, "auto", "ping", 1, false)).block(WAIT);

        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.model()).isEqualTo("Relay/Claude-Sonnet-4.5"));
        assertThat(relay.chats()).singleElement().satisfies(seen -> {
            assertThat(seen.body().path("model").asString("")).isEqualTo("Relay/Claude-Sonnet-4.5");
            assertThat(seen.authorization()).isEqualTo("Bearer sk-relay");
        });
    }

    // ---- the verification ----

    @Test
    void aPassedVerificationIsStoredUnderTheFingerprintAndEveryNodeSeesItWithoutTheVendorsVersionMoving() {
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        long configVersion = configVersion();
        long vendorVersion = vendorVersion();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isFalse();

        SourceTierDtos.VerifyResult result = verify(false);

        assertThat(result.verified()).isTrue();
        Stored stored = stored("claude-sonnet-4.5");
        assertThat(stored.verifiedAt()).isEqualTo(clock.instant());
        assertThat(stored.hash()).hasSize(64);
        assertThat(stored.summary().path("passed").booleanValue()).isTrue();
        assertThat(stored.summary().path("probes")).extracting(probe -> probe.path("name").asString(""))
            .containsExactly("basic", "tool_call", "tool_loop", "stream");
        assertThat(stored.summary().path("probes")).allSatisfy(probe -> assertThat(probe.path("status").asString("")).isEqualTo("PASSED"));
        assertThat(stored.summary().has("cache")).isFalse();
        assertThat(stored.summary().toString()).doesNotContain("relay.internal");
        // The registry of this node was told, the others are told by the configuration version, and the page being edited is not.
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isTrue();
        assertThat(configVersion()).isEqualTo(configVersion + 1);
        assertThat(vendorVersion()).isEqualTo(vendorVersion);
        SourceTierDtos.MemberModel shown = admin.overview().block().vendors().stream().filter(vendor -> vendor.vendor().equals("anthropic"))
            .findFirst().orElseThrow().members().getFirst().models().getFirst();
        assertThat(shown.verified()).isTrue();
        assertThat(shown.verifiedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aFailedVerificationTakesAnEarlierOneAway() {
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(verify(false).verified()).isTrue();

        relay.finishReason = false;
        SourceTierDtos.VerifyResult failed = verify(false);

        assertThat(failed.verified()).isFalse();
        Stored stored = stored("claude-sonnet-4.5");
        assertThat(stored.verifiedAt()).isNull();
        assertThat(stored.hash()).isNull();
        assertThat(stored.summary().path("passed").booleanValue()).isFalse();
        assertThat(stored.summary().path("probes").get(0).path("message").asString("")).contains("finish_reason");
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isFalse();
    }

    @Test
    void aMappingProbedUnderOneConfigurationIsNotVerifiedForAnother() throws Exception {
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        relay.delayMillis = 300;

        CompletableFuture<SourceTierDtos.VerifyResult> running = CompletableFuture.supplyAsync(() -> verify(false));
        Thread.sleep(400);
        // The account is pointed at another relay while the probes are on their way.
        db.sql("UPDATE proxy_accounts SET base_url = :url WHERE account_id = :id").bind("url", relay.url() + "/other/v1").bind("id", accountId)
            .fetch().rowsUpdated().block();
        SourceTierDtos.VerifyResult result = running.get();

        assertThat(result.verified()).isFalse();
        assertThat(result.models().getFirst().stale()).isTrue();
        assertThat(result.models().getFirst().probes()).allSatisfy(probe -> assertThat(probe.status()).isEqualTo("PASSED"));
        Stored stored = stored("claude-sonnet-4.5");
        assertThat(stored.hash()).isNull();
        assertThat(stored.verifiedAt()).isNull();
        assertThat(stored.summary().isEmpty()).isTrue();
    }

    @Test
    void aMemberChangedWhileItWasProbedIsNotVerifiedForTheNewSettings() throws Exception {
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        relay.delayMillis = 300;

        CompletableFuture<SourceTierDtos.VerifyResult> running = CompletableFuture.supplyAsync(() -> verify(false));
        Thread.sleep(400);
        SourceTierDtos.Member withVision = new SourceTierDtos.Member(accountId, "relay", "OPENAI_CHAT", null, true, null, null, null, true, null, null,
            true, true, List.of(mapping("claude-sonnet-4.5", "relay/sonnet")));
        save(1L, withVision);
        SourceTierDtos.VerifyResult result = running.get();

        assertThat(result.models().getFirst().stale()).isTrue();
        assertThat(stored("claude-sonnet-4.5").hash()).isNull();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isFalse();
    }

    @Test
    void whatHasNoRowAnyMoreIsNotWrittenAndNothingIsAnnounced() {
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        long before = configVersion();
        db.sql("DELETE FROM source_tier_members WHERE account_id = :id").bind("id", accountId).fetch().rowsUpdated().block();

        java.util.Map<String, SourceTierAdminService.Recorded> written = admin.recordVerification("anthropic", accountId, List.of(new SourceTierAdminService.Probed(
            "claude-sonnet-4.5", "0".repeat(64), true, clock.instant(), JSON.createObjectNode(), null))).block();

        assertThat(written).isEmpty();
        assertThat(configVersion()).as("nothing changed, so nobody is told").isEqualTo(before);
    }

    // ---- the evidence of the cache probe ----

    @Test
    void aNotContainedSettingIsSavedOnlyOnTheEvidenceOfTheCacheProbeAndThatEvidenceSurvivesTheSave() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));

        ApiException without = refusedSave(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(without.getCode()).isEqualTo("SOURCE_TIER_CACHE_EVIDENCE_REQUIRED");
        assertThat(without.getMessage()).contains(accountId).contains("claude-sonnet-4.5").contains("cache read and the cache write");
        assertThat(vendorVersion()).as("a refusal changes nothing").isEqualTo(1L);

        SourceTierDtos.ModelVerification probed = verify(true).models().getFirst();
        assertThat(probed.cache().readInPrompt()).isFalse();
        assertThat(probed.cache().writeInPrompt()).isFalse();
        JsonNode cache = stored("claude-sonnet-4.5").summary().path("cache");
        assertThat(cache.path("read").booleanValue()).isFalse();
        assertThat(cache.path("write").booleanValue()).isFalse();
        assertThat(cache.path("basis").asString("")).hasSize(64);

        save(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));

        // The setting changes what the verification vouches for, so that is gone; what the cache probe saw is not.
        Stored saved = stored("claude-sonnet-4.5");
        assertThat(saved.hash()).isNull();
        assertThat(saved.verifiedAt()).isNull();
        assertThat(saved.summary().path("cache").path("read").booleanValue()).isFalse();
        assertThat(saved.summary().has("probes")).isFalse();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isFalse();

        // Verified again under the new setting, without a cache probe: the evidence is kept next to the new findings.
        assertThat(verify(false).verified()).isTrue();
        Stored again = stored("claude-sonnet-4.5");
        assertThat(again.hash()).hasSize(64);
        assertThat(again.summary().path("cache")).isEqualTo(cache);
        assertThat(again.summary().path("probes")).isNotEmpty();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isTrue();
        // And a save that does not touch it keeps all of it.
        save(2L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(stored("claude-sonnet-4.5").summary()).isEqualTo(again.summary());
        assertThat(stored("claude-sonnet-4.5").hash()).isEqualTo(again.hash());
    }

    @Test
    void theEvidenceIsGoneWhenTheRelayMovesOrIsCalledByAnotherNameAndOnlyAChangeOfTheMappingAsksForItAgain() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        verify(true);
        save(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));

        // Another name on the relay: the evidence was about the first, so the setting cannot stay.
        ApiException renamed = refusedSave(2L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet-v2")));
        assertThat(renamed.getCode()).isEqualTo("SOURCE_TIER_CACHE_EVIDENCE_REQUIRED");

        // The account now points to another relay. The mapping is not verified any more, so it serves nothing until it is.
        db.sql("UPDATE proxy_accounts SET base_url = :url WHERE account_id = :id").bind("url", relay.url() + "/moved/v1").bind("id", accountId)
            .fetch().rowsUpdated().block();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isFalse();
        // A save that leaves the mapping as it is goes through: refusing it would stop every change to the vendor (a price
        // factor, a pin of another account), which nothing in the console can put right. The evidence is not carried.
        save(2L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(stored("claude-sonnet-4.5").hash()).isNull();
        assertThat(stored("claude-sonnet-4.5").summary().has("cache")).as("the evidence was about the relay it moved from").isFalse();
        // A change to the mapping asks for the evidence again, as there is none for this relay.
        ApiException changed = refusedSave(3L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet-v3")));
        assertThat(changed.getCode()).isEqualTo("SOURCE_TIER_CACHE_EVIDENCE_REQUIRED");
        // The administrator can always go back to the setting that needs none.
        save(3L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(stored("claude-sonnet-4.5").summary().has("cache")).as("and the stale evidence is not carried").isFalse();
    }

    @Test
    void aLaterCacheProbeThatSettlesNothingDoesNotTakeBackWhatAnEarlierOneShowedButAConclusionReplacesIt() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        verify(true);
        save(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));

        // A relay that did not find the prompt in its cache the second time says nothing, and nothing is taken back.
        relay.cache = RelaySimulator.Cache.NONE;
        SourceTierDtos.ModelVerification quiet = verify(true).models().getFirst();
        assertThat(quiet.cache().readInPrompt()).isNull();
        assertThat(quiet.verified()).isTrue();
        JsonNode kept = stored("claude-sonnet-4.5").summary().path("cache");
        assertThat(kept.path("read").booleanValue()).isFalse();
        assertThat(kept.path("write").booleanValue()).isFalse();
        save(2L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));

        // A probe that shows the opposite does: the prompt of this relay now contains the read.
        relay.cache = RelaySimulator.Cache.READ_IN_PROMPT;
        SourceTierDtos.ModelVerification contradicted = verify(true).models().getFirst();
        assertThat(contradicted.cache().readInPrompt()).isTrue();
        assertThat(contradicted.verified()).as("the setting says otherwise, and nothing shows it").isFalse();
        assertThat(contradicted.probes().getLast().name()).isEqualTo("cache_evidence");
        assertThat(contradicted.probes().getLast().message()).contains("not to contain the cache read");
        JsonNode replaced = stored("claude-sonnet-4.5").summary().path("cache");
        assertThat(replaced.path("read").booleanValue()).isTrue();
        assertThat(replaced.path("write").booleanValue()).as("what it said nothing of stands").isFalse();
        assertThat(refusedSave(3L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet"))).getCode())
            .isEqualTo("SOURCE_TIER_CACHE_EVIDENCE_REQUIRED");
    }

    @Test
    void aRelayThatMovedIsNotVerifiedAgainOnASettingNobodyShowedForTheNewAddress() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        verify(true);
        save(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));
        assertThat(verify(false).verified()).isTrue();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isTrue();

        // The account is pointed at another address (the same simulator, another path): every probe still passes there.
        db.sql("UPDATE proxy_accounts SET base_url = :url WHERE account_id = :id").bind("url", relay.url() + "/moved/v1").bind("id", accountId)
            .fetch().rowsUpdated().block();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).as("the address is part of the fingerprint").isFalse();

        SourceTierDtos.ModelVerification again = verify(false).models().getFirst();
        assertThat(again.probes().stream().filter(probe -> !probe.name().equals("cache_evidence")))
            .allSatisfy(probe -> assertThat(probe.status()).isEqualTo("PASSED"));
        assertThat(again.verified()).isFalse();
        assertThat(again.probes().getLast().name()).isEqualTo("cache_evidence");
        Stored stored = stored("claude-sonnet-4.5");
        assertThat(stored.hash()).isNull();
        assertThat(stored.summary().path("passed").booleanValue()).isFalse();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isFalse();

        // With the cache probe asked for the new address, the evidence is there, and the mapping is verified.
        SourceTierDtos.ModelVerification shown = verify(true).models().getFirst();
        assertThat(shown.cache().readInPrompt()).isFalse();
        assertThat(shown.verified()).isTrue();
        assertThat(verifiedInTheRegistry("claude-sonnet-4.5")).isTrue();
    }

    @Test
    void aCacheProbeThatCouldNotBeAnsweredLeavesTheEarlierEvidenceStanding() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));
        verify(true);
        save(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));

        relay.failFromChat = relay.chats().size() + 5;
        SourceTierDtos.ModelVerification model = verify(true).models().getFirst();

        assertThat(model.cache()).isNull();
        assertThat(model.probes().getLast().name()).isEqualTo("cache");
        assertThat(model.probes().getLast().status()).isEqualTo("FAILED");
        save(2L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet")));
    }

    @Test
    void everyMappingThatIsOnNeedsItsOwnEvidence() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet"), mapping("claude-haiku-4.5", "relay/haiku")));
        verify(true, "claude-sonnet-4.5");

        ApiException onlyOne = refusedSave(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet"), mapping("claude-haiku-4.5", "relay/haiku")));
        assertThat(onlyOne.getMessage()).contains("model claude-haiku-4.5");

        verify(true, "claude-haiku-4.5");
        save(1L, relayRow(false, false, mapping("claude-sonnet-4.5", "relay/sonnet"), mapping("claude-haiku-4.5", "relay/haiku")));
        assertThat(vendorVersion()).isEqualTo(2L);
    }

    @Test
    void aRelayWhosePromptContainsTheReadCanBeSavedAsContainedWithoutAnyEvidence() {
        relay.cache = RelaySimulator.Cache.READ_IN_PROMPT;
        save(0L, relayRow(true, true, mapping("claude-sonnet-4.5", "relay/sonnet")));

        SourceTierDtos.CacheAdvice advice = verify(true).models().getFirst().cache();

        assertThat(advice.readInPrompt()).isTrue();
        assertThat(advice.writeInPrompt()).isNull();
        // Containing is the safe setting and needs no evidence; saying the relay does not contain the read still needs it.
        assertThat(refusedSave(1L, relayRow(false, true, mapping("claude-sonnet-4.5", "relay/sonnet"))).getCode())
            .isEqualTo("SOURCE_TIER_CACHE_EVIDENCE_REQUIRED");
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-26T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
