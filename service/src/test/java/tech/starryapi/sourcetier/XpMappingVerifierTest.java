package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.ProxyAccountRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.openai.OpenAiRelayModelsClient;
import tech.starryapi.platform.xproto.RelayNotSentException;
import tech.starryapi.platform.xproto.StrictChatLint;
import tech.starryapi.sourcetier.SourceTierAdminService.Probed;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;

/**
 * The verification of a relay (design 7.4) against a relay on the loopback address that behaves, and can be told to
 * misbehave one probe at a time: what each probe concludes, that a failed one takes the verification away, what is
 * stored, the throttle, the selection of mappings, the cache advice, and that nothing the relay wrote reaches a report.
 * The storage itself is the administrator service's and is tested against PostgreSQL; here it is a stand-in.
 */
class XpMappingVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String LEAK = "relay.internal.example.com";

    private final SourceTierAdminService admin = mock(SourceTierAdminService.class);
    private final ProxyAccountRepository accounts = mock(ProxyAccountRepository.class);
    private final MutableClock clock = new MutableClock();
    private final List<RelaySimulator> open = new ArrayList<>();
    private final List<Probed> stored = new ArrayList<>();
    /** What the stand-in of the administrator service reports as written: every mapping, unless a test says otherwise. */
    private boolean everythingIsStale;
    /** What the stand-in reports as the "not contained" setting no evidence shows, for a mapping whose probes passed. */
    private String unprovenCache;

    private RelaySimulator relay;
    private ProxyAccountEntity account;
    private XpMappingVerifier verifier;

    @BeforeAll
    static void startTheHttpStack() throws Exception {
        try (RelaySimulator warm = new RelaySimulator()) {
            new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build()).complete(account(warm.url()),
                JSON.createObjectNode().put("model", "m").put("stream", false), Duration.ofSeconds(30)).onErrorResume(error -> Mono.empty()).block(WAIT);
        }
    }

    @BeforeEach
    void aGoodRelayAndAMemberThatUsesIt() throws Exception {
        relay = new RelaySimulator();
        open.add(relay);
        account = account(relay.url());
        when(accounts.findByAccountId("relay-1")).thenReturn(Mono.just(account));
        when(admin.storedSnapshot()).thenAnswer(invocation -> Mono.just(snapshot(member(true, true, true, false), model("claude-sonnet-4.5", "Relay/Claude-Sonnet-4.5", true))));
        when(admin.recordVerification(eq("anthropic"), eq("relay-1"), any())).thenAnswer(invocation -> {
            List<Probed> probed = invocation.getArgument(2);
            stored.addAll(probed);
            Map<String, SourceTierAdminService.Recorded> written = new HashMap<>();
            if (!everythingIsStale) {
                probed.forEach(run -> written.put(XpCacheEvidence.key("relay-1", run.modelId()),
                    new SourceTierAdminService.Recorded(run.passed() && unprovenCache == null, unprovenCache)));
            }
            return Mono.just(written);
        });
        verifier = new XpMappingVerifier(admin, accounts, new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build()),
            new OpenAiRelayModelsClient(JSON, ProductionWebClients.builder().build()), new DefaultModelCatalogProvider(), clock, Duration.ZERO);
    }

    @AfterEach
    void closeTheRelays() throws Exception {
        for (RelaySimulator simulator : open) {
            simulator.close();
        }
    }

    // ---- fixtures ----

    private static ProxyAccountEntity account(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("relay-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("sk-relay");
        account.setEnabled(true);
        return account;
    }

    private static Member member(boolean vision, boolean stream, boolean readInPrompt, boolean writeInPrompt) {
        return new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, vision, false, false, false, stream, "NONE", 540,
            readInPrompt, writeInPrompt);
    }

    private static MemberModel model(String id, String wire, boolean enabled) {
        return new MemberModel("anthropic", "relay-1", id, wire, 200_000, 8192, enabled, null, null);
    }

    private static SourceTierSnapshot snapshot(Member member, MemberModel... models) {
        return SourceTierSnapshot.of(1, List.of(VendorConfig.defaults("anthropic")),
            List.of(new Tier("anthropic", "relay", 1, "Relay", BigDecimal.ONE, Set.of(), true, true, null)),
            List.of(member), List.of(models), List.of());
    }

    private void use(Member member, MemberModel... models) {
        when(admin.storedSnapshot()).thenAnswer(invocation -> Mono.just(snapshot(member, models)));
    }

    private SourceTierDtos.VerifyResult ask(SourceTierDtos.VerifyMember request) {
        return verifier.verify("anthropic", "relay-1", request).block(WAIT);
    }

    private static SourceTierDtos.ProbeResult probe(SourceTierDtos.ModelVerification model, String name) {
        return model.probes().stream().filter(result -> result.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> statuses(SourceTierDtos.ModelVerification model) {
        return model.probes().stream().map(result -> result.name() + ":" + result.status()).toList();
    }

    // ---- a relay that behaves ----

    @Test
    void aGoodRelayPassesEveryProbeAndTheMappingIsStoredAsVerifiedUnderItsFingerprint() {
        SourceTierDtos.VerifyResult result = ask(null);

        assertThat(result.verified()).isTrue();
        assertThat(result.vendor()).isEqualTo("anthropic");
        assertThat(result.models()).singleElement().satisfies(model -> {
            assertThat(model.modelId()).isEqualTo("claude-sonnet-4.5");
            assertThat(model.upstreamModel()).isEqualTo("Relay/Claude-Sonnet-4.5");
            assertThat(model.verified()).isTrue();
            assertThat(model.stale()).isFalse();
            assertThat(statuses(model)).containsExactly("basic:PASSED", "tool_call:PASSED", "tool_loop:PASSED", "stream:PASSED", "vision:PASSED");
            assertThat(model.cache()).as("no cache probe was asked for").isNull();
            assertThat(probe(model, "basic").promptTokens()).isEqualTo(12);
            assertThat(probe(model, "basic").completionTokens()).isEqualTo(2);
            assertThat(probe(model, "tool_call").message()).isEqualTo("ok");
        });
        assertThat(stored).singleElement().satisfies(run -> {
            assertThat(run.passed()).isTrue();
            assertThat(run.modelId()).isEqualTo("claude-sonnet-4.5");
            assertThat(run.cache()).isNull();
            assertThat(run.at()).isEqualTo(clock.instant());
            assertThat(run.summary().path("passed").booleanValue()).isTrue();
            assertThat(run.summary().path("probes")).hasSize(5);
            // The hash is the one the planner and the console compare the stored mapping with.
            assertThat(run.fingerprint()).isEqualTo(SourceTierValidator.mappingFingerprint(
                member(true, true, true, false), model("claude-sonnet-4.5", "Relay/Claude-Sonnet-4.5", true), account));
        });
    }

    @Test
    void theRelayIsAskedWithTheKeyOfTheAccountTheWireNameAsWrittenAndNothingElse() {
        ask(null);

        assertThat(relay.chats()).hasSize(5);
        assertThat(relay.chats()).allSatisfy(seen -> {
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.path()).isEqualTo("/v1/chat/completions");
            assertThat(seen.authorization()).isEqualTo("Bearer sk-relay");
            assertThat(seen.body().path("model").asString("")).isEqualTo("Relay/Claude-Sonnet-4.5");
            assertThat(StrictChatLint.passes(seen.body())).isTrue();
        });
        // The short question once as JSON, the function twice and the stream once as the member reads answers, the image once.
        assertThat(relay.chats().stream().map(seen -> seen.body().path("stream").asBoolean(false)).toList())
            .containsExactly(false, true, true, true, true);
    }

    @Test
    void aMemberThatWantsNoStreamAndNoImageIsAskedThreeQuestions() {
        use(member(false, false, true, false), model("claude-sonnet-4.5", "Relay/Claude-Sonnet-4.5", true));

        SourceTierDtos.VerifyResult result = ask(null);

        assertThat(result.verified()).isTrue();
        assertThat(statuses(result.models().getFirst())).containsExactly("basic:PASSED", "tool_call:PASSED", "tool_loop:PASSED");
        assertThat(relay.chats()).hasSize(3);
    }

    @Test
    void everyMappingThatIsSwitchedOnIsProbedUnderItsOwnNameAndOneThatIsOffIsLeftAlone() {
        use(member(false, false, true, false), model("claude-sonnet-4.5", "relay/sonnet", true), model("claude-haiku-4.5", "relay/haiku", true),
            model("claude-opus-4.5", "relay/opus", false));

        SourceTierDtos.VerifyResult result = ask(null);

        assertThat(result.models()).extracting(SourceTierDtos.ModelVerification::modelId).containsExactly("claude-haiku-4.5", "claude-sonnet-4.5");
        assertThat(relay.chats().stream().map(seen -> seen.body().path("model").asString("")).distinct().toList())
            .containsExactly("relay/haiku", "relay/sonnet");
        assertThat(stored).extracting(Probed::modelId).containsExactly("claude-haiku-4.5", "claude-sonnet-4.5");
    }

    @Test
    void theMappingsAskedForAreTheOnesProbedEvenIfOneIsSwitchedOff() {
        use(member(false, false, true, false), model("claude-sonnet-4.5", "relay/sonnet", true), model("claude-opus-4.5", "relay/opus", false));

        SourceTierDtos.VerifyResult result = ask(new SourceTierDtos.VerifyMember(List.of("CLAUDE-OPUS-4.5"), false));

        assertThat(result.models()).extracting(SourceTierDtos.ModelVerification::modelId).containsExactly("claude-opus-4.5");
        assertThat(relay.chats().stream().map(seen -> seen.body().path("model").asString("")).distinct().toList()).containsExactly("relay/opus");
    }

    // ---- a relay that takes its time ----

    private XpMappingVerifier verifierWithLimits(Duration run, Duration shortProbe) {
        return new XpMappingVerifier(admin, accounts, new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build()),
            new OpenAiRelayModelsClient(JSON, ProductionWebClients.builder().build()), new DefaultModelCatalogProvider(), clock, Duration.ZERO,
            run, shortProbe);
    }

    @Test
    void aRunIsHeldToItsWholeTimeAndWhatItHadNoTimeToAskIsSkippedNotPassed() {
        // Every answer comes after 1.2 seconds, which is inside the limit of a question; the run has two.
        relay.delayMillis = 1200;
        verifier = verifierWithLimits(Duration.ofSeconds(2), Duration.ofSeconds(30));
        use(member(true, true, true, false), model("claude-sonnet-4.5", "relay/sonnet", true), model("claude-haiku-4.5", "relay/haiku", true));

        long started = System.nanoTime();
        SourceTierDtos.VerifyResult result = ask(null);
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.verified()).isFalse();
        assertThat(result.models()).extracting(SourceTierDtos.ModelVerification::modelId).containsExactly("claude-haiku-4.5", "claude-sonnet-4.5");
        SourceTierDtos.ModelVerification first = result.models().get(0);
        assertThat(statuses(first)).containsExactly("basic:PASSED", "tool_call:SKIPPED", "tool_loop:SKIPPED", "stream:SKIPPED", "vision:SKIPPED");
        assertThat(probe(first, "tool_call").message()).isEqualTo("Not asked: the verification ran out of time");
        assertThat(first.verified()).isFalse();
        assertThat(statuses(result.models().get(1))).containsExactly("basic:SKIPPED", "tool_call:SKIPPED", "tool_loop:SKIPPED", "stream:SKIPPED", "vision:SKIPPED");
        assertThat(relay.chats()).as("the relay is not asked what there is no time for").hasSize(1);
        assertThat(tookMillis).isLessThan(Duration.ofSeconds(10).toMillis());
        assertThat(stored).extracting(Probed::passed).containsExactly(false, false);
    }

    @Test
    void aQuestionIsAskedWithTheTimeLeftOfTheRunAndNotAskedAtAllWhenLessThanASecondIsLeft() {
        // Every answer takes 0.7 seconds of the 1.9 the run has: 1.2 seconds are left for the second question, 0.5 for the third.
        relay.delayMillis = 700;
        verifier = verifierWithLimits(Duration.ofMillis(1900), Duration.ofSeconds(30));
        use(member(false, false, true, false), model("claude-sonnet-4.5", "relay/sonnet", true));

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        // The third would start with half a second, which is less than the second a question needs to be asked.
        assertThat(statuses(model)).containsExactly("basic:PASSED", "tool_call:PASSED", "tool_loop:SKIPPED");
        assertThat(model.verified()).isFalse();
    }

    @Test
    void aShortQuestionThatTakesLongerThanItsLimitFailsAndTheRestIsNotAsked() {
        relay.delayMillis = 1500;
        verifier = verifierWithLimits(Duration.ofMinutes(4), Duration.ofMillis(400));

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(statuses(model)).containsExactly("basic:FAILED", "tool_call:SKIPPED", "tool_loop:SKIPPED", "stream:SKIPPED", "vision:SKIPPED");
        assertThat(probe(model, "basic").message()).contains("time limit").doesNotContain("127.0.0.1");
        assertThat(model.verified()).isFalse();
    }

    // ---- a relay that does not ----

    @Test
    void aShortAnswerWithoutAFinishReasonFailsTheBasicProbeAndTakesTheVerificationAway() {
        relay.finishReason = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(probe(model, "basic").status()).isEqualTo("FAILED");
        assertThat(probe(model, "basic").message()).contains("finish_reason");
        assertThat(model.verified()).isFalse();
        assertThat(stored).singleElement().satisfies(run -> {
            assertThat(run.passed()).isFalse();
            assertThat(run.summary().path("passed").booleanValue()).isFalse();
        });
    }

    @Test
    void anAnswerWithoutUsageFailsTheBasicProbeAndTheRestIsNotAskedForNothing() {
        relay.usage = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(probe(model, "basic").message()).contains("usage");
        assertThat(statuses(model)).containsExactly("basic:FAILED", "tool_call:SKIPPED", "tool_loop:SKIPPED", "stream:SKIPPED", "vision:SKIPPED");
        assertThat(probe(model, "tool_call").message()).contains("basic probe failed");
        assertThat(relay.chats()).as("one question, since the relay does not answer the first").hasSize(1);
    }

    @Test
    void aRelayThatAnswersInTextWhereAFunctionWasToBeCalledFailsTheToolProbe() {
        relay.callsTheFunction = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(statuses(model)).contains("basic:PASSED", "tool_call:FAILED");
        assertThat(probe(model, "tool_call").message()).contains("did not call the function");
        assertThat(model.verified()).isFalse();
    }

    @Test
    void functionArgumentsThatAreNotJsonFailTheToolProbe() {
        relay.validArguments = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(probe(model, "tool_call").status()).isEqualTo("FAILED");
        assertThat(probe(model, "tool_call").message()).contains("tool call that is not valid");
        assertThat(model.verified()).isFalse();
    }

    @Test
    void aRelayThatDoesNotAnswerAfterAToolResultFailsTheLoopProbe() {
        relay.answersAfterTheResult = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(statuses(model)).contains("tool_call:PASSED", "tool_loop:FAILED");
        assertThat(model.verified()).isFalse();
    }

    @Test
    void aStreamWithoutUsageFailsTheStreamProbeOnly() {
        relay.usageInStreams = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(statuses(model)).containsExactly("basic:PASSED", "tool_call:PASSED", "tool_loop:PASSED", "stream:FAILED", "vision:PASSED");
        assertThat(probe(model, "stream").message()).contains("usage");
        assertThat(model.verified()).isFalse();
    }

    @Test
    void aRelayThatDoesNotServeTheNameFailsWithItsStatusAndTypeAndNeverItsWords() {
        relay.unknownModels.add("Relay/Claude-Sonnet-4.5");

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(probe(model, "basic").message()).isEqualTo("Source error 404 (model_not_found)");
        assertThat(model.verified()).isFalse();
    }

    @Test
    void whateverTheRelaySaysIsNeverInAReportOrInWhatIsStored() {
        relay.errorStatus = 500;

        SourceTierDtos.VerifyResult result = ask(null);

        assertThat(result.verified()).isFalse();
        assertThat(JSON.writeValueAsString(result)).doesNotContain(LEAK).doesNotContain("broken");
        assertThat(stored.getFirst().summary().toString()).doesNotContain(LEAK).doesNotContain("broken");
    }

    @Test
    void aRelayThatCannotBeReachedIsAFailureOfTheProbeNotOfTheVerification() {
        relay.close();

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(probe(model, "basic").status()).isEqualTo("FAILED");
        assertThat(probe(model, "basic").message()).doesNotContain("127.0.0.1");
        assertThat(model.verified()).isFalse();
    }

    // ---- the cache probe ----

    @Test
    void theCacheProbeIsOnlyAskedForWhenAskedFor() {
        ask(new SourceTierDtos.VerifyMember(null, false));

        assertThat(relay.chats()).hasSize(5);
    }

    @Test
    void aRelayThatKeepsItsCacheOutOfThePromptIsAdvisedOnBothSwitches() {
        relay.cache = RelaySimulator.Cache.NOT_IN_PROMPT;

        SourceTierDtos.ModelVerification model = ask(new SourceTierDtos.VerifyMember(null, true)).models().getFirst();

        assertThat(model.verified()).isTrue();
        assertThat(probe(model, "cache").status()).isEqualTo("PASSED");
        assertThat(model.cache().readInPrompt()).isFalse();
        assertThat(model.cache().writeInPrompt()).isFalse();
        assertThat(model.cache().readEvidence()).contains("cache read").contains("does not contain");
        assertThat(model.cache().calls()).hasSize(2);
        assertThat(model.cache().calls().get(0).cacheWriteTokens()).isPositive();
        assertThat(model.cache().calls().get(1).cacheReadTokens()).isPositive();
        assertThat(stored.getFirst().cache().readInPrompt()).isFalse();
        // Two prompts of about 1,200 tokens, the same both times, asked after the five probes of the rest.
        List<String> prompts = relay.chats().stream().skip(5).map(seen -> seen.body().path("messages").get(0).path("content").asString("")).toList();
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0)).isEqualTo(prompts.get(1)).hasSizeGreaterThan(4000);
    }

    @Test
    void aRelayWhosePromptContainsTheReadIsAdvisedOnTheReadOnly() {
        relay.cache = RelaySimulator.Cache.READ_IN_PROMPT;

        SourceTierDtos.CacheAdvice cache = ask(new SourceTierDtos.VerifyMember(null, true)).models().getFirst().cache();

        assertThat(cache.readInPrompt()).isTrue();
        assertThat(cache.writeInPrompt()).isNull();
    }

    @Test
    void aRelayThatCachesNothingGivesNoAdviceAndTheMappingStillPasses() {
        SourceTierDtos.ModelVerification model = ask(new SourceTierDtos.VerifyMember(null, true)).models().getFirst();

        assertThat(model.cache().readInPrompt()).isNull();
        assertThat(model.cache().message()).contains("no cached tokens");
        assertThat(model.verified()).isTrue();
        assertThat(stored.getFirst().cache()).isNotNull();
    }

    @Test
    void aCacheProbeThatCannotBeAnsweredFailsOnlyItselfAndLeavesTheEvidenceThereWasAlone() {
        relay.failFromChat = 7;

        SourceTierDtos.ModelVerification model = ask(new SourceTierDtos.VerifyMember(null, true)).models().getFirst();

        assertThat(probe(model, "cache").status()).isEqualTo("FAILED");
        assertThat(model.cache()).isNull();
        assertThat(model.verified()).as("the cache probe is advice").isTrue();
        assertThat(stored.getFirst().cache()).isNull();
        assertThat(stored.getFirst().passed()).isTrue();
    }

    @Test
    void theCacheProbeIsNotAskedOfARelayThatFailedTheBasicOne() {
        relay.usage = false;

        SourceTierDtos.ModelVerification model = ask(new SourceTierDtos.VerifyMember(null, true)).models().getFirst();

        assertThat(statuses(model)).contains("cache:SKIPPED");
        assertThat(relay.chats()).hasSize(1);
    }

    // ---- changes while it runs ----

    @Test
    void aMappingThatChangedWhileItWasProbedIsReportedStaleAndNotVerified() {
        everythingIsStale = true;

        SourceTierDtos.VerifyResult result = ask(null);

        assertThat(result.verified()).isFalse();
        assertThat(result.models().getFirst().stale()).isTrue();
        assertThat(result.models().getFirst().verified()).isFalse();
        assertThat(result.models().getFirst().probes()).allSatisfy(probe -> assertThat(probe.status()).isEqualTo("PASSED"));
    }

    @Test
    void aMappingWhoseProbesPassedButWhoseNotContainedSettingNothingShowsIsNotVerified() {
        unprovenCache = "cache read";

        SourceTierDtos.VerifyResult result = ask(null);

        assertThat(result.verified()).isFalse();
        SourceTierDtos.ModelVerification model = result.models().getFirst();
        assertThat(model.verified()).isFalse();
        assertThat(model.stale()).isFalse();
        assertThat(statuses(model)).containsExactly("basic:PASSED", "tool_call:PASSED", "tool_loop:PASSED", "stream:PASSED", "vision:PASSED",
            "cache_evidence:FAILED");
        assertThat(probe(model, "cache_evidence").message()).contains("not to contain the cache read").contains("verify with the cache probe");
    }

    @Test
    void aFailedProbeIsNotBlamedOnTheCacheSettingToo() {
        unprovenCache = "cache read";
        relay.usage = false;

        SourceTierDtos.ModelVerification model = ask(null).models().getFirst();

        assertThat(statuses(model)).doesNotContain("cache_evidence:FAILED");
    }

    // ---- the throttle ----

    @Test
    void anAccountIsVerifiedOnceInThirtySeconds() {
        ask(null);

        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(429);
            assertThat(error.getCode()).isEqualTo("SOURCE_VERIFY_RATE_LIMITED");
            assertThat(error.getDetails()).isEqualTo(Map.of("retryAfterSeconds", 30L));
        });
        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getDetails()).isEqualTo(Map.of("retryAfterSeconds", 1L)));
        int asked = relay.chats().size();

        clock.advance(Duration.ofSeconds(1));
        assertThat(ask(null).verified()).isTrue();
        assertThat(relay.chats().size()).as("a refused call asks the relay nothing").isGreaterThan(asked);
    }

    @Test
    void anAccountIsNeverVerifiedTwiceAtTheSameTimeEvenAfterTheIntervalHasPassed() throws Exception {
        relay.delayMillis = 300;
        CompletableFuture<SourceTierDtos.VerifyResult> first = CompletableFuture.supplyAsync(() -> ask(null));
        Thread.sleep(150);
        clock.advance(Duration.ofSeconds(45));

        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getCode()).isEqualTo("SOURCE_VERIFY_RATE_LIMITED");
            assertThat(error.getMessage()).contains("still being probed");
        });
        assertThat(first.get().verified()).isTrue();
        // It is over, and it began long ago.
        relay.delayMillis = 0;
        assertThat(ask(null).verified()).isTrue();
    }

    @Test
    void aVerificationThatFailsStillCountsAgainstTheInterval() {
        relay.errorStatus = 500;
        ask(null);
        relay.errorStatus = 0;

        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_VERIFY_RATE_LIMITED"));
    }

    @Test
    void anotherAccountIsNotHeldUpByTheThrottleOfThisOne() {
        ask(null);
        ProxyAccountEntity other = account(relay.url());
        other.setAccountId("relay-2");
        when(accounts.findByAccountId("relay-2")).thenReturn(Mono.just(other));
        when(admin.storedSnapshot()).thenAnswer(invocation -> Mono.just(snapshot(
            new Member("anthropic", "relay-2", "relay", "OPENAI_CHAT", true, false, false, false, false, false, "NONE", 540, true, true),
            new MemberModel("anthropic", "relay-2", "claude-sonnet-4.5", "relay/sonnet", 1000, 1, true, null, null))));
        when(admin.recordVerification(eq("anthropic"), eq("relay-2"), any())).thenReturn(Mono.just(
            Map.of(XpCacheEvidence.key("relay-2", "claude-sonnet-4.5"), new SourceTierAdminService.Recorded(true, null))));

        assertThat(verifier.verify("anthropic", "relay-2", null).block(WAIT).verified()).isTrue();
    }

    // ---- what can be verified ----

    @Test
    void anAccountThatIsNoRelayOfTheVendorIsNotFoundAndCostsNothing() {
        assertThatThrownBy(() -> verifier.verify("openai", "relay-1", null).block(WAIT)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_MEMBER_NOT_FOUND"));
        when(accounts.findByAccountId("ghost")).thenReturn(Mono.empty());
        assertThatThrownBy(() -> verifier.verify("anthropic", "ghost", null).block(WAIT)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getStatus().value()).isEqualTo(404));
        when(admin.storedSnapshot()).thenAnswer(invocation -> Mono.just(SourceTierSnapshot.EMPTY));
        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_MEMBER_NOT_FOUND"));

        assertThat(relay.requests()).isEmpty();
        verify(admin, never()).recordVerification(any(), any(), any());
        // The refusals did not use up the interval.
        when(admin.storedSnapshot()).thenAnswer(invocation -> Mono.just(snapshot(member(false, false, true, false), model("claude-sonnet-4.5", "relay/sonnet", true))));
        assertThat(ask(null).verified()).isTrue();
    }

    @Test
    void aNativePinIsNoRelay() {
        Member pin = new Member("anthropic", "relay-1", "relay", null, true, false, false, false, false, true, "NONE", 540, null, null);
        when(admin.storedSnapshot()).thenAnswer(invocation -> Mono.just(snapshot(pin)));

        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_MEMBER_NOT_FOUND"));
    }

    @Test
    void anAccountThatIsNotACompatibleKeyOrHasNoKeyIsRefused() {
        ProxyAccountEntity official = account("https://api.openai.com/v1");
        official.setProvider("OpenAI");
        when(accounts.findByAccountId("relay-1")).thenReturn(Mono.just(official));
        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getMessage()).contains("not an OpenAI-compatible API key"));

        ProxyAccountEntity keyless = account(relay.url());
        keyless.setAccessToken(" ");
        when(accounts.findByAccountId("relay-1")).thenReturn(Mono.just(keyless));
        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getMessage()).contains("no API key"));
        assertThat(relay.requests()).isEmpty();
    }

    @Test
    void aVendorThatIsNotOneIsRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> verifier.verify("Not A Vendor!", "relay-1", null).block(WAIT)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_VENDOR_UNKNOWN"));
        verify(admin, never()).storedSnapshot();
    }

    @Test
    void theMappingsToVerifyAreChosenWithCare() {
        use(member(false, false, true, false), model("claude-sonnet-4.5", "relay/sonnet", true));
        assertThatThrownBy(() -> ask(new SourceTierDtos.VerifyMember(List.of("claude-haiku-4.5"), false))).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getMessage()).contains("no mapping for the model claude-haiku-4.5"));

        use(member(false, false, true, false), model("claude-sonnet-4.5", "relay/sonnet", false));
        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getMessage()).contains("no mapping that is switched on"));

        MemberModel[] many = new MemberModel[XpMappingVerifier.MAX_MAPPINGS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = model("claude-model-" + i, "relay/model-" + i, true);
        }
        use(member(false, false, true, false), many);
        assertThatThrownBy(() -> ask(null)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getMessage()).contains("at most 8").contains("modelIds"));
        assertThat(relay.requests()).isEmpty();
    }

    // ---- the relay's models ----

    @Test
    void theModelsOfARelayAreListedForTheMappingDialogWithoutTheAccountBeingAMember() {
        relay.listsModels("{\"data\":[{\"id\":\"relay/sonnet\",\"context_length\":200000},{\"id\":\"relay/haiku\"}]}");

        SourceTierDtos.UpstreamModels models = verifier.upstreamModels("anthropic", "relay-1").block(WAIT);

        assertThat(models.truncated()).isFalse();
        assertThat(models.models()).extracting(SourceTierDtos.UpstreamModel::id, SourceTierDtos.UpstreamModel::contextLength)
            .containsExactly(tuple("relay/sonnet", 200000), tuple("relay/haiku", null));
        assertThat(relay.requests()).singleElement().satisfies(seen -> {
            assertThat(seen.method()).isEqualTo("GET");
            assertThat(seen.path()).isEqualTo("/v1/models");
            assertThat(seen.authorization()).isEqualTo("Bearer sk-relay");
        });
        verify(admin, never()).storedSnapshot();
    }

    @Test
    void theListOfModelsIsOneInFiveSecondsPerAccountAndNotTheVerificationsThrottle() {
        verifier.upstreamModels("anthropic", "relay-1").block(WAIT);

        assertThatThrownBy(() -> verifier.upstreamModels("anthropic", "relay-1").block(WAIT)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_VERIFY_RATE_LIMITED"));
        assertThat(ask(null).verified()).as("listing the models did not use the verification's interval").isTrue();
        clock.advance(Duration.ofSeconds(5));
        assertThat(verifier.upstreamModels("anthropic", "relay-1").block(WAIT).models()).isEmpty();
    }

    @Test
    void aRelayThatDoesNotListIsA502WithTheStatusAndNotItsWords() {
        relay.errorStatus = 401;
        relay.errorCode = "invalid_api_key";

        assertThatThrownBy(() -> verifier.upstreamModels("anthropic", "relay-1").block(WAIT)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(502);
            assertThat(error.getCode()).isEqualTo("SOURCE_UPSTREAM_MODELS_FAILED");
            assertThat(error.getMessage()).contains("Source error 401").doesNotContain(LEAK).doesNotContain("broken");
        });
    }

    @Test
    void theVendorOfAListMustBeOneARelayCanServe() {
        assertThatThrownBy(() -> verifier.upstreamModels("deepseek", "relay-1").block(WAIT)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("SOURCE_PROFILE_NOT_APPLICABLE"));
        when(accounts.findByAccountId("ghost")).thenReturn(Mono.empty());
        assertThatThrownBy(() -> verifier.upstreamModels("anthropic", "ghost").block(WAIT)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getStatus().value()).isEqualTo(404));
        assertThat(relay.requests()).isEmpty();
    }

    // ---- what is handed to the storage ----

    @Test
    void theStorageIsToldTheFingerprintTheProbesWereRunUnderAndNotOneItComputesLater() {
        ArgumentCaptor<List<Probed>> captured = ArgumentCaptor.forClass(List.class);

        ask(null);

        verify(admin).recordVerification(eq("anthropic"), eq("relay-1"), captured.capture());
        assertThat(captured.getValue()).singleElement().satisfies(run -> assertThat(run.fingerprint()).hasSize(64).matches("[0-9a-f]+"));
    }

    @Test
    void aProbeThatNeverLeftIsDescribedInTheGatewaysWordsAndNotByTheClassOfTheTransportError() {
        RelayNotSentException notSent = new RelayNotSentException(new java.net.ConnectException("Connection refused: relay.internal.example.com"));

        assertThat(XpMappingVerifier.describe(notSent)).isEqualTo("The source could not be reached");
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
