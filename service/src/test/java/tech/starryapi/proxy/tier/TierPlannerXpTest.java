package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.xproto.CodexRequests;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.platform.xproto.ResponsesChatConverter;
import tech.starryapi.platform.xproto.StrictChatLint;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierPlan.Relay;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.sourcetier.SourceTierValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The relays of a vendor in its plans (design 3, steps 4 and 5b): when a relay is a cell of a tier, which of its
 * mappings serves a model, what is checked before it may serve a request, what it is sent, why it is left out, and what a
 * request that nothing else can serve is told. The configuration is the owner's example: a relay as the first tier of the
 * anthropic vendor, Kiro as the second.
 */
class TierPlannerXpTest {

    private static final AccountAllocationCategory KIRO = AccountAllocationCategory.KIRO;
    private static final AccountAllocationCategory CLAUDE_OAUTH = AccountAllocationCategory.CLAUDE_OAUTH;
    private static final BigDecimal BILLING_MULTIPLIER = new BigDecimal("1.5");
    /** $3/M in, $15/M out, $0.30/M cache read, $3.75/M cache write: the Sonnet example of the design. */
    private static final BillingQuote STANDARD = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    /** A registry of a release that offers no pair. */
    private static final PairRegistry NO_PAIR = new PairRegistry(List.of());
    private static final PairKey MESSAGES_PAIR = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic");
    private static final PairKey CHAT_PAIR = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic");

    /** A Claude Code main-loop request: a system prompt with a cache mark, adaptive thinking and a tool. */
    private static final String CLAUDE_CODE = """
        {"model":"claude-sonnet-4.5","max_tokens":4096,"stream":true,
         "metadata":{"user_id":"user_0123456789abcdef_account__session_00000000-0000-0000-0000-000000000000"},
         "thinking":{"type":"adaptive"},"output_config":{"effort":"medium"},
         "context_management":{"edits":[{"type":"clear_thinking_20251015"}]},
         "system":[{"type":"text","text":"You are Claude Code.","cache_control":{"type":"ephemeral"}}],
         "tools":[{"name":"Bash","description":"Runs a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}}}}],
         "messages":[{"role":"user","content":"list the files"}]}
        """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(catalog);
    private final AnthropicRequestAdapter adapter = new AnthropicRequestAdapter(mapper, catalog);
    private final ResponsesChatConverter converter = new ResponsesChatConverter(mapper);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final XpSkipCounts counts = new XpSkipCounts();
    /** What the node remembers of the relays: the clock of both stores is this one. */
    private final AtomicLong nanos = new AtomicLong();
    private final TierSessionBindings sessions = new TierSessionBindings(new ProxyProperties(), nanos::get);
    private final XpLearnedLimits limits = new XpLearnedLimits(nanos::get);

    /** Every account the account cache holds (enabled), whatever its state. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** The accounts of the cache that are available (not suspended, not out of quota). */
    private final List<ProxyAccountEntity> usable = new ArrayList<>();
    /** The accounts that are switched off: in no cache, but in the table. */
    private final List<ProxyAccountEntity> switchedOff = new ArrayList<>();

    private final ProxyAccountEntity kiro = account("kiro", "BuilderId", "idc", null);
    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key", null);
    private final ProxyAccountEntity relay = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private final ProxyAccountEntity secondRelay = account("relay-2", "OpenAI-compatible", "api-key", "https://relay-two.example/v1");

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation -> usable.contains(invocation.<ProxyAccountEntity>getArgument(0)));
        when(accountPool.switchedOffAccounts()).thenAnswer(invocation -> Mono.just(List.copyOf(switchedOff)));
        cached.addAll(List.of(kiro, relay));
        usable.addAll(cached);
    }

    // ---- the owner's example: a relay as the first tier, Kiro as the second ----

    @Test
    void aClaudeCodeRequestIsPlannedOverTheRelayAndThenKiro() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        Level first = plan.levels().get(0);
        Level second = plan.levels().get(1);
        // The route's own cells come first and the relays' last; the second tier has no relay.
        assertThat(first.cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE, PoolCell.XP_OPENAI_CHAT);
        assertThat(second.cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE);
        assertThat(second.relays()).isEmpty();
        assertThat(plan.xpSkips()).isEmpty();
        assertThat(first.factor()).isEqualByComparingTo("1");
        assertThat(second.factor()).isEqualByComparingTo("0.6");
        // Neither the tier of the relay nor the first has been pushed out of: both are in the plan, and the walk starts at the relay.
        assertThat(plan.startLevel().tierId()).isEqualTo("relay");
        assertThat(plan.unavailableTiers()).isEmpty();
    }

    @Test
    void theRelayIsSentTheWhitelistedBodyUnderTheNameOfItsMapping() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));

        Relay chosen = plan.levels().get(0).relay("relay-1").orElseThrow();
        TierDispatch dispatch = chosen.dispatch();
        assertThat(dispatch.kind()).isEqualTo(TierDispatch.Kind.OPENAI_CHAT);
        assertThat(dispatch.pair()).isEqualTo(MESSAGES_PAIR);
        assertThat(dispatch.modelId()).isEqualTo("claude-sonnet-4.5");
        assertThat(dispatch.wireModel()).isEqualTo("relay-sonnet");
        assertThat(dispatch.outgoingEndpoint()).isEqualTo("/chat/completions");
        assertThat(chosen.accountId()).isEqualTo("relay-1");
        assertThat(chosen.mapping().maxContextTokens()).isEqualTo(200_000);
        JsonNode body = dispatch.wireBody();
        assertThat(StrictChatLint.problems(body)).isEmpty();
        // The dispatcher writes the model name, and the member asks for a stream with usage.
        assertThat(body.has("model")).isFalse();
        assertThat(body.path("stream").booleanValue()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").booleanValue()).isTrue();
        assertThat(body.path("max_tokens").intValue()).isEqualTo(4096);
        assertThat(body.toString()).doesNotContain("user_id", "context_management", "cache_control");
        // What the relay was not given is recorded for the request log, in the words of the policy.
        assertThat(dispatch.degraded()).contains("metadata", "context_management", "cache_control", "thinking");
    }

    @Test
    void anOpenCodeRequestOnTheChatEntryIsPlannedOverTheRelayAndKiroToo() {
        configure(ownerConfiguration(relayMember(), gate("CHAT", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), chat(chatBody("claude-sonnet-4.5")));

        // Chat Completions takes the Kiro route for a Claude model, which has the one cell.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(plan.levels().get(0).cells()).containsExactly(PoolCell.KIRO_COMPATIBLE, PoolCell.XP_OPENAI_CHAT);
        assertThat(plan.levels().get(1).cells()).containsExactly(PoolCell.KIRO_COMPATIBLE);
        Relay chosen = plan.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(chosen.dispatch().pair()).isEqualTo(CHAT_PAIR);
        assertThat(chosen.dispatch().wireModel()).isEqualTo("relay-sonnet");
        assertThat(StrictChatLint.problems(chosen.dispatch().wireBody())).isEmpty();
    }

    @Test
    void thePlanKnowsWhichAccountsEachCellMayServe() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));
        Level first = plan.levels().get(0);
        Level second = plan.levels().get(1);

        assertThat(plan.permits(first, PoolCell.XP_OPENAI_CHAT, relay)).isTrue();
        assertThat(plan.permits(first, PoolCell.XP_OPENAI_CHAT, secondRelay)).isFalse();
        assertThat(plan.permits(first, PoolCell.XP_OPENAI_CHAT, kiro)).isFalse();
        assertThat(plan.permits(second, PoolCell.XP_OPENAI_CHAT, relay)).isFalse();
        // A relay is no native member of any tier, so the native cells never take it.
        assertThat(plan.holds(first, relay)).isFalse();
        assertThat(plan.permits(first, PoolCell.KIRO_COMPATIBLE, relay)).isFalse();
        assertThat(plan.permits(second, PoolCell.KIRO_COMPATIBLE, kiro)).isTrue();
    }

    @Test
    void aTierHoldsAnAccountAsAMemberOfTheCellItIsOfNotAsAnotherCellsMember() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));
        Level first = plan.levels().get(0);
        Level second = plan.levels().get(1);

        assertThat(plan.holds(first, PoolCell.XP_OPENAI_CHAT, relay)).isTrue();
        assertThat(plan.holds(first, PoolCell.XP_OPENAI_CHAT, secondRelay)).isFalse();
        assertThat(plan.holds(second, PoolCell.XP_OPENAI_CHAT, relay)).isFalse();
        assertThat(plan.holds(second, PoolCell.KIRO_COMPATIBLE, kiro)).isTrue();
        assertThat(plan.holds(first, PoolCell.KIRO_COMPATIBLE, kiro)).isFalse();
        assertThat(plan.holds(first, PoolCell.KIRO_COMPATIBLE, relay)).isFalse();
    }

    @Test
    void aRelayKnowsTheMostInputItCanBeSentAndItsTierReservesForIt() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));
        Level first = plan.levels().get(0);
        Relay chosen = first.relay("relay-1").orElseThrow();

        assertThat(chosen.inputBound()).isEqualTo(RequestBounds.xpInputTokens(chosen.dispatch().wireBody(), 8192));
        assertThat(first.inputBound()).isGreaterThanOrEqualTo(chosen.inputBound());
    }

    @Test
    void aRelayThatIsOutOfServiceIsLeftOutOfThePlanButStillOneForTheRetestToTest() {
        cached.remove(relay);
        usable.remove(relay);
        switchedOff.add(relay);
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        // It cannot serve this request, whose plan was made without it, but the tier never comes back unless it is tested.
        assertThat(plan.leftOutRelay(relay)).isTrue();
        // Not a relay of the vendor, not a relay at all, not an account at all.
        assertThat(plan.leftOutRelay(secondRelay)).isFalse();
        assertThat(plan.leftOutRelay(kiro)).isFalse();
        assertThat(plan.leftOutRelay(null)).isFalse();
        // The retest of the native accounts that are left out is a different question.
        assertThat(plan.leftOut(relay)).isFalse();
    }

    @Test
    void aRelayWhosePairIsNotOpenToTheKeyIsNotOneToTestForTheRequest() {
        cached.remove(relay);
        usable.remove(relay);
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "another-key")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));
        TierPlan pairNotOffered = plan(new PairRegistry(List.of()), messages(CLAUDE_CODE));

        assertThat(plan.leftOutRelay(relay)).isFalse();
        assertThat(pairNotOffered.leftOutRelay(relay)).isFalse();
    }

    @Test
    void aRelayThePlanLookedAtIsNotLeftOut() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));
        TierPlan inThePlan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));
        // Left out for what the request asks: a test of the account would change nothing.
        TierPlan skipped = plan(offering(Status.EXPERIMENTAL), messages(
            CLAUDE_CODE.replace("\"tools\"", "\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://mcp.example\",\"name\":\"m\"}],\"tools\"")));

        assertThat(inThePlan.leftOutRelay(relay)).isFalse();
        assertThat(skipped.xpSkips()).extracting(XpSkip::accountId).contains("relay-1");
        assertThat(skipped.leftOutRelay(relay)).isFalse();
    }

    @Test
    void theReservationOfARelayTierIsThePriceOfItsFactor() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE));

        Level first = plan.levels().get(0);
        Level second = plan.levels().get(1);
        BillingQuote standard = STANDARD.scaled(BigDecimal.ONE);
        assertThat(first.estimate()).isEqualByComparingTo(standard.estimate(first.inputBound(), 4096));
        assertThat(second.estimate()).isEqualByComparingTo(STANDARD.scaled(new BigDecimal("0.6")).estimate(second.inputBound(), 4096));
        assertThat(plan.reservation()).isEqualByComparingTo(first.estimate().max(second.estimate()));
    }

    // ---- no relay: the plan is the one without relays ----

    @Test
    void aVendorWithoutRelaysIsPlannedAsBefore() {
        cached.add(officialAnthropic);
        usable.add(officialAnthropic);
        configure(nativeConfiguration(List.of(), List.of(), List.of()));
        TierPlan withFacts = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));
        TierPlan withoutFacts = plan(offering(Status.VERIFIED), messagesWithoutFacts(CLAUDE_CODE));

        assertThat(withFacts.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
        assertSamePlan(withFacts, withoutFacts);
        assertThat(withFacts.levels()).allSatisfy(level -> assertThat(level.relays()).isEmpty());
        assertThat(withFacts.xpSkips()).isEmpty();
        assertThat(counts.snapshot()).isEmpty();
    }

    @Test
    void aRegisteredRelayLeavesThePlanAsItWasWhereNoPairIsOffered() {
        cached.add(officialAnthropic);
        usable.add(officialAnthropic);
        configure(nativeConfiguration(List.of(), List.of(), List.of()));
        TierPlan before = plan(NO_PAIR, messages(CLAUDE_CODE));

        Member member = relayMember("relay-1", false, "official");
        configure(nativeConfiguration(List.of(member), List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)),
            List.of(gate("MESSAGES", "ON"))));
        TierPlan after = plan(NO_PAIR, messages(CLAUDE_CODE));

        assertSamePlan(after, before);
        assertThat(after.xpSkips()).isEmpty();
        assertThat(counts.snapshot()).isEmpty();
    }

    @Test
    void aRegisteredRelayTakesNoPartInARequestThatWasNotReadForRelays() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON", "key-1")));

        TierPlan plan = plan(offering(Status.VERIFIED), messagesWithoutFacts(CLAUDE_CODE));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.levels().get(0).cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE);
        assertThat(plan.xpSkips()).isEmpty();
    }

    @Test
    void aPairThatIsNotOfferedIsNotLookedAtAndLeavesNoTrace() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON", "key-1")));

        TierPlan plan = plan(NO_PAIR, messages(CLAUDE_CODE));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).isEmpty();
        assertThat(counts.snapshot()).isEmpty();
    }

    @Test
    void theRegistryOfThisReleaseGivesTheListedKeysOfAnExperimentalPairTheRelay() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1"), gate("CHAT", "KEYS", "key-1")));

        assertThat(servedByRelay(plan(PairRegistry.defaults(), messages(CLAUDE_CODE, "key-1")))).isTrue();
        assertThat(servedByRelay(plan(PairRegistry.defaults(), chat(chatBody("claude-sonnet-4.5"))))).isTrue();
        assertThat(servedByRelay(plan(PairRegistry.defaults(), messages(CLAUDE_CODE, "key-2")))).isFalse();
        // An experimental pair is not opened to everyone by a gate that says ON.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        assertThat(servedByRelay(plan(PairRegistry.defaults(), messages(CLAUDE_CODE, "key-2")))).isFalse();
    }

    @Test
    void theEntryOfAnotherPairThanTheOnesOfferedGetsNoRelay() {
        configure(ownerConfiguration(relayMember(), gate("CHAT", "ON", "key-1")));

        // Only Messages is offered; the Chat request has facts but its pair is not listed.
        TierPlan plan = plan(offering(Status.VERIFIED, Entry.MESSAGES), chat(chatBody("claude-sonnet-4.5")));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).isEmpty();
    }

    // ---- the registry and the gate ----

    @Test
    void anExperimentalPairIsOpenToTheKeysItsGateListsAndToNoOneElse() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1", "key-7")));

        assertThat(servedByRelay(plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "key-1")))).isTrue();
        assertThat(servedByRelay(plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "key-7")))).isTrue();
        TierPlan stranger = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "key-2"));
        assertThat(servedByRelay(stranger)).isFalse();
        assertThat(stranger.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.GATE);
        assertThat(stranger.xpSkips().getFirst().tierId()).isEqualTo("relay");
        assertThat(stranger.xpSkips().getFirst().accountId()).isEqualTo("relay-1");
    }

    @Test
    void aGateThatIsOnOpensAVerifiedPairToEveryKey() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));

        assertThat(servedByRelay(plan(offering(Status.VERIFIED), messages(CLAUDE_CODE, "key-1")))).isTrue();
        assertThat(servedByRelay(plan(offering(Status.VERIFIED), messages(CLAUDE_CODE, "key-2")))).isTrue();
        assertThat(servedByRelay(plan(offering(Status.VERIFIED), messages(CLAUDE_CODE, null)))).isTrue();
    }

    @Test
    void aGateThatIsOnCannotOpenAPairThatIsOnlyExperimentalBeyondItsListedKeys() {
        // The administrator API refuses this combination; a pair that was verified once and no longer is, or a gate stored
        // by another release, must not open it all the same: the code's state is the first lock.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON", "key-1")));

        assertThat(servedByRelay(plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "key-1")))).isTrue();
        assertThat(servedByRelay(plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "key-2")))).isFalse();
    }

    @Test
    void aGateThatIsOffOrMissingOrAnotherEntrysOpensNothing() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "OFF", "key-1")));
        TierPlan off = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));
        assertThat(servedByRelay(off)).isFalse();
        // A gate that is off is not a key left off a list: the relay is not looked at, and not counted as left out.
        assertThat(off.xpSkips()).isEmpty();

        configure(ownerConfiguration(relayMember()));
        assertThat(servedByRelay(plan(offering(Status.VERIFIED), messages(CLAUDE_CODE)))).isFalse();

        configure(ownerConfiguration(relayMember(), gate("CHAT", "ON")));
        assertThat(servedByRelay(plan(offering(Status.VERIFIED), messages(CLAUDE_CODE)))).isFalse();
    }

    @Test
    void aGateThatIsOffForAVendorIsNoGateSkipOfItsRelaysWhateverAnotherVendorOpensForTheSameEntry() {
        Member anthropicRelay = relayMember();
        Member openAiRelay = new Member("openai", "relay-2", "relay", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
        cached.add(secondRelay);
        usable.add(secondRelay);
        cached.add(openAiOfficial);
        usable.add(openAiOfficial);
        List<Tier> tiers = new ArrayList<>(anthropicTiers());
        tiers.addAll(List.of(tier("openai", "relay", 1, "relay", "1"),
            tier("openai", "official", 2, "official", "0.5", AccountAllocationCategory.OFFICIAL_API_KEY)));
        Function<PairGate, SourceTierSnapshot> configured = openAiGate -> SourceTierSnapshot.of(7L,
            List.of(vendor("anthropic"), vendor("openai")), tiers, List.of(anthropicRelay, openAiRelay),
            List.of(mapping(anthropicRelay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
                mapping(openAiRelay, "gpt-5.5", "relay-gpt", 400_000, true)),
            openAiGate == null ? List.of(gateOf("anthropic", "CHAT", "KEYS", "key-1"))
                : List.of(gateOf("anthropic", "CHAT", "KEYS", "key-1"), openAiGate));

        // Anthropic opens the Chat entry to its key, so every Chat request is read for relays; the gpt request of any key then
        // reaches the openai vendor, whose gate is off or was never made: its relay is not one this key was kept from.
        for (PairGate closed : new PairGate[] {null, gateOf("openai", "CHAT", "OFF")}) {
            configure(configured.apply(closed));
            TierPlan gpt = plan(offering(Status.VERIFIED), openAiChat(chatBody("gpt-5.5")));

            assertThat(gpt.levels()).allSatisfy(level -> assertThat(level.relays()).isEmpty());
            assertThat(gpt.xpSkips()).isEmpty();
            assertThat(counts.snapshot()).isEmpty();
        }

        // A gate that is open to some keys and not to this one is the case the count is for.
        configure(configured.apply(gateOf("openai", "CHAT", "KEYS", "key-9")));
        TierPlan unlisted = plan(offering(Status.VERIFIED), openAiChat(chatBody("gpt-5.5")));
        assertThat(unlisted.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.GATE);
        assertThat(counts.snapshot()).containsExactly(Map.entry(new XpSkipCounts.Key("openai", "relay", XpSkip.Reason.GATE), 1L));
    }

    @Test
    void theReasonsRelaysAreLeftOutAreCountedPerVendorTierAndReason() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "another-key")));

        plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));
        plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        assertThat(counts.snapshot()).containsExactly(Map.entry(new XpSkipCounts.Key("anthropic", "relay", XpSkip.Reason.GATE), 2L));
    }

    // ---- the account and the mapping ----

    @Test
    void anAccountThatIsNoLongerARelayIsSkipped() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        relay.setBaseUrl("https://api.openai.com/v1");
        relay.setProvider("OpenAI");

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.NOT_A_RELAY);
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aRelayThatIsNotInTheAccountCacheIsNotMemberOfAnything() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        cached.remove(relay);
        switchedOff.add(relay);

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        // Neither a tier nor a reason: a relay that is switched off is as good as not registered until it is back.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).isEmpty();
    }

    @Test
    void aModelWithoutAMappingIsSkippedAndAnAliasTakesTheRowOfTheModelItStandsFor() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));

        TierPlan opus = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE.replace("claude-sonnet-4.5", "claude-opus-4.5"), "claude-opus-4.5"));
        assertThat(opus.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.NO_MAPPING);

        // "sonnet" is the Claude Code alias of the default Sonnet: it is served by the mapping of claude-sonnet-4.5, and the
        // plan names the model by the model it stands for.
        TierPlan alias = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE.replace("claude-sonnet-4.5", "sonnet"), "sonnet"));
        assertThat(alias.levels().get(0).relay("relay-1")).isPresent();
        assertThat(alias.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireModel()).isEqualTo("relay-sonnet");
        assertThat(alias.modelKey()).isEqualTo("claude-sonnet-4.5");
    }

    /**
     * A relay left out for good (no mapping for the model, not verified, out of the cache) is nothing the exclusion is checked
     * for, but the exclusion it may be under is the session's all the same: the next turn of the chain may ask for a model it
     * serves, and then it is the same session.
     */
    @Test
    void aRelayThatSitsATurnOutForWhatHoldsOfTheSessionAndModelStillHasTheSessionItsExclusionIsKeptUnder() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        String opusTurn = CLAUDE_CODE.replace("claude-sonnet-4.5", "claude-opus-4.5");

        TierPlan noMapping = planRemembering(messages(opusTurn).withSessionName("key-1:session-1"));
        assertThat(noMapping.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.NO_MAPPING);
        assertThat(noMapping.relaySession()).isEqualTo("key-1:session-1");

        cached.remove(relay);
        switchedOff.add(relay);
        TierPlan outOfService = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));
        assertThat(outOfService.xpSkips()).isEmpty();
        assertThat(outOfService.relaySession()).isEqualTo("key-1:session-1");

        // A relay that no gate lets the key through to is nothing of the session's.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "another-key")));
        assertThat(planRemembering(messages(opusTurn).withSessionName("key-1:session-1")).relaySession()).isNull();
    }

    @Test
    void theRowOfTheAliasItselfWinsOverTheRowOfTheModelItStandsFor() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(
            mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
            mapping(member, "sonnet", "relay-sonnet-alias", 200_000, true)), gate("MESSAGES", "ON")));

        TierPlan alias = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE.replace("claude-sonnet-4.5", "sonnet"), "sonnet"));
        TierPlan sonnet = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        assertThat(alias.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireModel()).isEqualTo("relay-sonnet-alias");
        assertThat(sonnet.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireModel()).isEqualTo("relay-sonnet");
    }

    @Test
    void aMappingThatIsSwitchedOffIsNotReplacedByTheRowOfAnotherModel() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(
            mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
            mapping(member, "sonnet", "relay-sonnet-alias", 200_000, false)), gate("MESSAGES", "ON")));

        TierPlan alias = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE.replace("claude-sonnet-4.5", "sonnet"), "sonnet"));

        assertThat(alias.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.NO_MAPPING);
        assertThat(alias.levels()).extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void theIdOfAnotherVendorIsNotAnAliasOfAMappingOfThisOne() {
        XpModelResolver resolver = new XpModelResolver(catalog, keyResolver);
        Member member = relayMember();
        MemberModel claude = mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true);

        // The catalog says gemini-2.5-pro is served with a Claude model by the Kiro route, but it is Google's model.
        assertThat(resolver.find(List.of(claude), "google", "gemini-2.5-pro")).isEmpty();
        assertThat(catalog.targetFor("gemini-2.5-pro")).isPresent();
        assertThat(resolver.find(List.of(claude), "anthropic", "sonnet")).contains(claude);
        assertThat(resolver.find(List.of(claude), "anthropic", "claude-sonnet-4.5")).contains(claude);
        assertThat(resolver.find(List.of(claude), "anthropic", "claude-opus-4.5")).isEmpty();
    }

    @Test
    void aMappingThatWasNeverVerifiedOrWhoseFingerprintChangedIsSkipped() {
        Member member = relayMember();
        MemberModel unverified = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, 8192, true, null, null);
        configure(ownerConfiguration(member, List.of(unverified), gate("MESSAGES", "ON")));
        TierPlan never = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));
        assertThat(never.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.UNVERIFIED);
        assertThat(never.levels()).extracting(Level::tierId).containsExactly("kiro");

        // Verified for another address: what it vouched for is not what it is now.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        relay.setBaseUrl("https://moved.example/v1");
        TierPlan moved = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));
        assertThat(moved.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.UNVERIFIED);
    }

    // ---- what the request asks for ----

    @Test
    void aSoftFeatureIsRecordedAndStillServedByTheRelay() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").insertObject(0).put("type", "tool_search_tool_regex_20251119").put("name", "tool_search_tool_regex");

        TierPlan plan = plan(offering(Status.VERIFIED), messages(raw.toString()));

        Relay served = plan.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(served.dispatch().degraded()).contains("tool_search_tool");
        assertThat(served.dispatch().wireBody().toString()).doesNotContain("tool_search");
    }

    @Test
    void aFeatureTheNativeAccountsCarryLeavesTheRelayOutAndKiroServes() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        TierPlan plan = plan(offering(Status.VERIFIED), messages(raw.toString()));

        assertThat(plan.xpSkips()).hasSize(1);
        assertThat(plan.xpSkips().getFirst().reason()).isEqualTo(XpSkip.Reason.FEATURE);
        assertThat(plan.xpSkips().getFirst().firstFeature()).isEqualTo("web_search");
        // The request is not refused, nor is the relay's tier a tier the session was pushed out of (design 4.7): it is
        // not in the plan at all, for this request.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.unavailableAbove(plan.levels().get(0))).isFalse();
    }

    @Test
    void aRequestOnlyTheRelaysTierCouldServeIsRefusedWithTheFeatureNamed() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        cached.remove(kiro);
        usable.remove(kiro);
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        assertThatThrownBy(() -> plan(offering(Status.VERIFIED), messages(raw.toString())))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_FEATURE_UNSUPPORTED");
                assertThat(error.getMessage()).contains("web_search").doesNotContain("relay");
                assertThat(error.getDetails()).isEqualTo(Map.of("feature", "web_search"));
            });
    }

    @Test
    void aRequestThatNothingCanServeForAnotherReasonIsLeftToTheWalkAsItWasBefore() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "OFF")));
        cached.remove(kiro);
        usable.remove(kiro);

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        // No account anywhere: every tier stays, marked as having no member, and the walk finds none.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(plan.levels()).allSatisfy(level -> assertThat(plan.memberless(level)).isTrue());
        assertThat(plan.levels().get(0).relays()).isEmpty();
    }

    @Test
    void aFastRequestNeedsARelayWithAPriorityTier() {
        // The openai vendor's models are the ones that are billed as fast.
        Member plain = openAiRelayMember(false);
        configureOpenAi(plain, gateOf("openai", "CHAT", "ON"));
        ObjectNode body = chatBody("gpt-5.5");
        TierPlan refused = planOpenAiChat(body, true);
        assertThat(refused.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.FEATURE);
        assertThat(refused.xpSkips().getFirst().detail()).isEqualTo("fast");

        Member priority = openAiRelayMember(true);
        configureOpenAi(priority, gateOf("openai", "CHAT", "ON"));
        TierPlan carried = planOpenAiChat(body, true);
        assertThat(carried.xpSkips()).isEmpty();
        assertThat(carried.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireBody().path("service_tier").stringValue())
            .isEqualTo("priority");
    }

    @Test
    void aGptRequestKeepsItsImageOnlyForARelayThatSeesImages() {
        // A gpt request goes to accounts that are handed the whole body: a relay that cannot see images is not given one.
        configureOpenAi(openAiRelayMember(false), gateOf("openai", "CHAT", "ON"));
        ObjectNode withImage = chatBody("gpt-5.5");
        withImage.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64,AAAA");

        TierPlan blind = planOpenAiChat(withImage, false);
        assertThat(blind.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.FEATURE);
        assertThat(blind.xpSkips().getFirst().detail()).isEqualTo("image");

        Member seeing = openAiRelayMember(false, true);
        configureOpenAi(seeing, gateOf("openai", "CHAT", "ON"));
        TierPlan sees = planOpenAiChat(withImage, false);
        assertThat(sees.xpSkips()).isEmpty();
        assertThat(sees.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireBody().toString()).contains("image_url");
    }

    // ---- the settings of the body ----

    @Test
    void membersWithTheSameSettingsAreSentTheSameBodyAndOthersTheirOwn() {
        Member first = relayMember("relay-1", false);
        Member second = relayMember("relay-2", false);
        Member seeing = relayMember("relay-3", true);
        ProxyAccountEntity third = account("relay-3", "OpenAI-compatible", "api-key", "https://relay-three.example/v1");
        cached.addAll(List.of(secondRelay, third));
        usable.addAll(List.of(secondRelay, third));
        configure(ownerConfigurationWith(List.of(first, second, seeing), List.of(
            mapping(first, relay, "claude-sonnet-4.5", "a-sonnet", 200_000, true),
            mapping(second, secondRelay, "claude-sonnet-4.5", "b-sonnet", 200_000, true),
            mapping(seeing, third, "claude-sonnet-4.5", "c-sonnet", 200_000, true)), gate("MESSAGES", "ON")));

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        Level relays = plan.levels().get(0);
        assertThat(relays.relays()).hasSize(3);
        JsonNode one = relays.relay("relay-1").orElseThrow().dispatch().wireBody();
        JsonNode two = relays.relay("relay-2").orElseThrow().dispatch().wireBody();
        JsonNode three = relays.relay("relay-3").orElseThrow().dispatch().wireBody();
        assertThat(two).isSameAs(one);
        // The settings differ (vision), and the policy and body are judged apart, though this request has no image.
        assertThat(three).isNotSameAs(one);
        assertThat(three).isEqualTo(one);
        assertThat(relays.relay("relay-2").orElseThrow().dispatch().wireModel()).isEqualTo("b-sonnet");
    }

    @Test
    void theReasoningLevelFollowsTheVendorOfTheModel() {
        // A Claude model after a tool call needs the thinking of the turn before, which a relay's history does not have:
        // the level is not asked for, and the policy and the sanitizer agree that it was left out.
        Member effort = relayMemberWith("relay-1", false, "EFFORT");
        configure(ownerConfigurationWith(List.of(effort), List.of(mapping(effort, relay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)),
            gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("messages").addObject().put("role", "assistant").putArray("content").addObject()
            .put("type", "tool_use").put("id", "toolu_01").put("name", "Bash").putObject("input").put("command", "ls");
        raw.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "tool_result").put("tool_use_id", "toolu_01").put("content", "a.txt");

        TierPlan continuation = plan(offering(Status.VERIFIED), messages(raw.toString()));
        TierPlan firstTurn = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        Relay afterTool = continuation.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(afterTool.dispatch().wireBody().has("reasoning_effort")).isFalse();
        assertThat(afterTool.dispatch().degraded()).contains("thinking");
        Relay first = firstTurn.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(first.dispatch().wireBody().path("reasoning_effort").stringValue()).isEqualTo("medium");
    }

    @Test
    void anOpenAiModelTakesItsReasoningLevelOnEveryTurnAndItsTokenLimitUnderTheOtherName() {
        Member effort = openAiRelayMemberWith(false, false, "EFFORT");
        configureOpenAi(effort, gateOf("openai", "CHAT", "ON"));
        ObjectNode body = chatBody("gpt-5.5");
        body.put("reasoning_effort", "high");
        body.withArray("messages").addObject().put("role", "assistant").putArray("tool_calls").addObject()
            .put("id", "call_1").put("type", "function").putObject("function").put("name", "Bash").put("arguments", "{}");
        body.withArray("messages").addObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "a.txt");

        TierPlan plan = planOpenAiChat(body, false);

        JsonNode wire = plan.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireBody();
        assertThat(wire.path("reasoning_effort").stringValue()).isEqualTo("high");
        assertThat(wire.has("max_tokens")).isFalse();
        assertThat(wire.path("max_completion_tokens").intValue()).isEqualTo(4096);
    }

    // ---- conversion and window ----

    @Test
    void aRequestThatDoesNotPassTheLintIsSkippedForTheConversion() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("name", "").put("description", "nameless");

        TierPlan plan = plan(offering(Status.VERIFIED), messages(raw.toString()));

        assertThat(plan.xpSkips()).hasSize(1);
        assertThat(plan.xpSkips().getFirst().reason()).isEqualTo(XpSkip.Reason.CONVERSION);
        assertThat(plan.xpSkips().getFirst().detail()).contains("function name");
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aRequestLargerThanTheWindowOfTheMappingIsSkippedAndASmallOneIsNot() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("messages").insertObject(0).put("role", "user").put("content", "word ".repeat(2_000));

        TierPlan large = plan(offering(Status.VERIFIED), messages(raw.toString()));
        TierPlan small = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        assertThat(large.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONTEXT);
        assertThat(large.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(small.xpSkips()).isEmpty();
        assertThat(small.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
    }

    @Test
    void aRelayThatSeesImagesIsReservedForByItsImageCapAndNotTheMillionTokenFloor() {
        // The Kiro cell of a Chat request sees text only, so its bound does not grow with an image; a relay with vision
        // would otherwise lift the reservation of its tier to the floor for hidden input (design 5.2).
        Member seeing = relayMember("relay-1", true);
        configure(ownerConfiguration(seeing, List.of(mapping(seeing, "claude-sonnet-4.5", "relay-sonnet", 200_000, 5_000, true)),
            gate("CHAT", "ON")));
        ObjectNode body = chatBody("claude-sonnet-4.5");
        body.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + "A".repeat(100_000));

        TierPlan plan = plan(offering(Status.VERIFIED), chat(body));

        Level first = plan.levels().get(0);
        Level second = plan.levels().get(1);
        assertThat(first.relays()).hasSize(1);
        assertThat(second.inputBound()).isLessThan(5_000);
        assertThat(first.inputBound()).isBetween(5_000L, 20_000L);
    }

    // ---- availability, D4 and the cells of a tier ----

    @Test
    void aRelayThatCannotServeRightNowKeepsItsTierInTheOrderOfThePlanAsAnUnavailableOne() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        usable.remove(relay);

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        // Out of quota is the availability kind of empty: the session that Kiro then serves has been through the relay's tier.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("relay");
        assertThat(plan.unavailableAbove(plan.levels().get(0))).isTrue();
    }

    @Test
    void aTierOfRelaysOnlyHasNoNativeMemberAndKiroKeepsItsOwn() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        assertThat(plan.holds(plan.levels().get(1), kiro)).isTrue();
        assertThat(plan.holds(plan.levels().get(0), kiro)).isFalse();
        assertThat(plan.levels().get(0).relays()).extracting(Relay::accountId).containsExactly("relay-1");
    }

    @Test
    void aCompatibleKeyThatIsNoRelayIsNotTakenForAnAccountOfATierThatWasLeftOut() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ProxyAccountEntity glm = account("glm", "GLM", "api-key", "https://open.bigmodel.cn/api/paas/v4");
        // Kiro has nothing to serve with, so its tier is left out of the plan; its own accounts are what a retest is for.
        usable.remove(kiro);

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(plan.leftOut(kiro)).isTrue();
        assertThat(plan.leftOut(glm)).isFalse();
        assertThat(plan.leftOut(relay)).isFalse();
    }

    @Test
    void aClaudeRequestOnChatDoesNotSkipARelayForAnImageTheNativeAccountsDoNotSeeEither() {
        // Chat Completions takes the Kiro route for a Claude model, which reads the text of a request and nothing else.
        Member blind = relayMember("relay-1", false);
        configure(ownerConfiguration(blind, List.of(mapping(blind, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gate("CHAT", "ON")));
        ObjectNode body = chatBody("claude-sonnet-4.5");
        body.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64,AAAA");

        TierPlan plan = plan(offering(Status.VERIFIED), chat(body));

        Relay served = plan.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(plan.xpSkips()).isEmpty();
        assertThat(served.dispatch().degraded()).contains("image");
        assertThat(served.dispatch().wireBody().toString()).doesNotContain("image_url");
    }

    @Test
    void theAllEndpointsSwitchMakesAClaudeChatRequestARequestOfTheAnthropicRouteForTheRelaysToo() {
        Member member = relayMember();
        configure(SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, true, false, true, 16, true, 7L)),
            anthropicTiers(), List.of(member), List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)),
            List.of(gate("CHAT", "ON"))));

        TierPlan plan = plan(offering(Status.VERIFIED), chat(chatBody("claude-sonnet-4.5")));

        assertThat(plan.unified()).isTrue();
        assertThat(plan.levels().get(0).cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE, PoolCell.XP_OPENAI_CHAT);
        assertThat(plan.levels().get(0).relay("relay-1").orElseThrow().dispatch().pair()).isEqualTo(CHAT_PAIR);
    }

    @Test
    void aHistoryWithToolCallsAndNoToolsIsMadeTextOnlyForTheVendorWhoseUpstreamNeedsToolsForIt() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.remove("tools");
        raw.withArray("messages").addObject().put("role", "assistant").putArray("content").addObject()
            .put("type", "tool_use").put("id", "toolu_01").put("name", "Bash").putObject("input").put("command", "ls");
        raw.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "tool_result").put("tool_use_id", "toolu_01").put("content", "a.txt");

        TierPlan claude = plan(offering(Status.VERIFIED), messages(raw.toString()));

        Relay served = claude.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(served.dispatch().degraded()).contains("tool_history_flattened");
        assertThat(served.dispatch().wireBody().toString()).doesNotContain("tool_calls", "\"role\":\"tool\"");

        // The same history of a gpt model stays structured: its upstream takes it.
        Member gpt = openAiRelayMember(false);
        configureOpenAi(gpt, gateOf("openai", "CHAT", "ON"));
        ObjectNode body = chatBody("gpt-5.5");
        body.withArray("messages").addObject().put("role", "assistant").putArray("tool_calls").addObject()
            .put("id", "call_1").put("type", "function").putObject("function").put("name", "Bash").put("arguments", "{}");
        body.withArray("messages").addObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "a.txt");
        TierPlan gptPlan = planOpenAiChat(body, false);
        Relay structured = gptPlan.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(structured.dispatch().degraded()).doesNotContain("tool_history_flattened");
        assertThat(structured.dispatch().wireBody().toString()).contains("tool_calls");
    }

    @Test
    void aRequestThatIsNotBilledIsPlannedWithRelaysToo() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        TierPlanRequest unbilled = new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, false,
            adapter.messagesToChat(json(CLAUDE_CODE)), null, false, "anonymous", null)
            .withEntry(TierEntry.MESSAGES).withEntryFacts(TierEntryFacts.of(TierEntry.MESSAGES, json(CLAUDE_CODE)));

        TierPlan plan = plan(offering(Status.VERIFIED), unbilled);

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(plan.reservation()).isNull();
        assertThat(plan.levels().get(0).estimate()).isNull();
        assertThat(plan.levels().get(0).relays()).hasSize(1);
    }

    // ---- the tier named by the client ----

    @Test
    void aClientThatPinsTheRelayTierByTheSuffixGetsItWhenItCanServe() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE)
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay")));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(plan.levels().get(0).relays()).hasSize(1);
    }

    @Test
    void aPinToTheRelayTierWithAFeatureItCannotCarryIsRefusedWithTheFeatureNamed() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        assertThatThrownBy(() -> plan(offering(Status.VERIFIED), messages(raw.toString())
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay"))))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_FEATURE_UNSUPPORTED");
                assertThat(error.getDetails()).isEqualTo(Map.of("feature", "web_search"));
            });
        // The tier above it is not the one named, so the same request pinned to Kiro is served.
        assertThat(plan(offering(Status.VERIFIED), messages(raw.toString())
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "kiro"))).levels())
            .extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aPinToTheRelayTierWhoseGateIsClosedIsNotOffered() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));

        assertThatThrownBy(() -> plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "key-2")
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay"))))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED");
            });
    }

    @Test
    void aHintForTheRelayTierThatCannotBeFollowedIsDroppedAndTheRequestGoesToKiro() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        // The header is written once for every request the client makes, the web search ones included.
        TierPlan plan = plan(offering(Status.VERIFIED), messages(raw.toString())
            .withSelector(TierSelectorParser.fromHeader("relay")));

        assertThat(plan.pinned()).isFalse();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, TierResponseHeaders.IGNORED);
    }

    @Test
    void aHintForTheRelayTierWhoseRelayIsSwitchedOffStaysPinnedAndIsUnavailable() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        cached.remove(relay);
        switchedOff.add(relay);

        TierPlan plan = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE)
            .withSelector(TierSelectorParser.fromHeader("relay")));

        // The pool exists and its relay is out of service for now: the answer is that it cannot serve, not Kiro's price.
        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay");
    }

    // ---- what the node has learned of the relays (design 7.8) ----

    @Test
    void aRelayThatRefusedAPromptForItsLengthIsLeftOutOfRequestsAsLargeAndStaysInForSmallerOnes() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        Relay served = planRemembering(messages(conversationOf(4_000))).levels().get(0).relay("relay-1").orElseThrow();
        assertThat(served.estimatedInput()).isBetween(4_000L, 6_000L);
        // The relay refused this very prompt: its window is smaller than the administrator wrote.
        limits.learn(served.mapping(), served.estimatedInput());

        TierPlan same = planRemembering(messages(conversationOf(4_000)));
        TierPlan larger = planRemembering(messages(conversationOf(5_000)));
        TierPlan smaller = planRemembering(messages(conversationOf(3_000)));

        assertThat(same.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONTEXT);
        assertThat(same.xpSkips().getFirst().detail()).contains("refused");
        assertThat(same.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(larger.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONTEXT);
        assertThat(larger.levels()).extracting(Level::tierId).containsExactly("kiro");
        // A short request is the relay's again, and the one that fits the window it was shown to have is not asked about.
        assertThat(smaller.xpSkips()).isEmpty();
        assertThat(smaller.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
    }

    @Test
    void aRelayLeftOutForAWindowItShowedIsNoTierTheSessionWasPushedOutOf() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        Relay served = planRemembering(messages(conversationOf(4_000))).levels().get(0).relay("relay-1").orElseThrow();
        limits.learn(served.mapping(), served.estimatedInput());

        TierPlan plan = planRemembering(messages(conversationOf(4_000)));

        // The reason is the request's and the relay's, as a window the administrator wrote is: Kiro serving it pushes nobody down.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.startIndex()).isZero();
        assertThat(new TierWalk(plan).realDowngradeTo(plan.levels().get(0))).isFalse();
    }

    @Test
    void whatARelayRefusedIsForgottenAfterSixHoursAndNeverHeldAgainstItsNeighbour() {
        Member first = relayMember("relay-1", false);
        Member second = relayMember("relay-2", false);
        cached.add(secondRelay);
        usable.add(secondRelay);
        configure(ownerConfigurationWith(List.of(first, second), List.of(
            mapping(first, relay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
            mapping(second, secondRelay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gate("MESSAGES", "ON")));
        Relay served = planRemembering(messages(conversationOf(4_000))).levels().get(0).relay("relay-1").orElseThrow();
        limits.learn(served.mapping(), served.estimatedInput());

        TierPlan plan = planRemembering(messages(conversationOf(4_000)));

        // Only the relay that refused it is left out: the next one of the tier is asked.
        assertThat(plan.xpSkips()).extracting(XpSkip::accountId).containsExactly("relay-1");
        assertThat(plan.levels().get(0).relays()).extracting(Relay::accountId).containsExactly("relay-2");

        nanos.addAndGet(java.time.Duration.ofHours(6).plusSeconds(1).toNanos());
        assertThat(planRemembering(messages(conversationOf(4_000))).levels().get(0).relays())
            .extracting(Relay::accountId).containsExactly("relay-1", "relay-2");
    }

    @Test
    void theWindowTheAdministratorWroteIsTheFirstReasonAndTheLearnedOneComesAfterIt() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));
        Relay small = planRemembering(messages(CLAUDE_CODE)).levels().get(0).relay("relay-1").orElseThrow();
        limits.learn(small.mapping(), 900);

        TierPlan large = planRemembering(messages(conversationOf(4_000)));

        assertThat(large.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONTEXT);
        assertThat(large.xpSkips().getFirst().detail()).contains("the window is 1000").doesNotContain("refused");
    }

    @Test
    void aRelayThatBrokeOffAnAnswerForTheSessionIsLeftOutOfItsNextPlansAndOnlyOfThose() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        TierPlan first = planRemembering(messages(CLAUDE_CODE));
        assertThat(first.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        // What the plan calls the session is what the facade writes the interruption under.
        assertThat(first.relaySession()).isNotNull();
        sessions.excludeRelay("anthropic", first.relaySession(), "relay-1");

        TierPlan retry = planRemembering(messages(CLAUDE_CODE));
        TierPlan anotherSession = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:another-session"));
        TierPlan anotherKey = planRemembering(messages(CLAUDE_CODE, "key-2"));

        assertThat(retry.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
        assertThat(retry.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(anotherSession.xpSkips()).isEmpty();
        assertThat(anotherSession.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(anotherKey.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");

        nanos.addAndGet(java.time.Duration.ofSeconds(121).toNanos());
        assertThat(planRemembering(messages(CLAUDE_CODE)).levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
    }

    @Test
    void theSessionTheClientNamedIsTheOneTheRelayIsKeptFrom() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));

        TierPlan plan = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));

        assertThat(plan.relaySession()).isEqualTo("key-1:session-1");
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");
        assertThat(planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1")).xpSkips())
            .extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
    }

    @Test
    void theRetryOfAnInterruptedRequestGoesToTheNextRelayOfTheTierBeforeAnyOtherTier() {
        Member first = relayMember("relay-1", false);
        Member second = relayMember("relay-2", false);
        cached.add(secondRelay);
        usable.add(secondRelay);
        configure(ownerConfigurationWith(List.of(first, second), List.of(
            mapping(first, relay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
            mapping(second, secondRelay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        TierPlan plan = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(plan.levels().get(0).relays()).extracting(Relay::accountId).containsExactly("relay-2");
        assertThat(plan.xpSkips()).extracting(XpSkip::accountId).containsExactly("relay-1");
    }

    @Test
    void aRelayLeftOutForHavingBrokenOffAnAnswerIsNoTierTheSessionWasPushedOutOf() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        TierPlan plan = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));

        // Not a tier that was found with nothing to offer: the session Kiro then serves is not bound to it (design 4.7, I8).
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.startIndex()).isZero();
        assertThat(new TierWalk(plan).realDowngradeTo(plan.levels().get(0))).isFalse();
    }

    @Test
    void aFeatureTheRelayCannotCarryIsStillWhatTheRequestIsToldWhenTheRelayWasAlsoInterrupted() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        TierPlan plan = planRemembering(messages(raw.toString()).withSessionName("key-1:session-1"));

        // The feature is the request's own and does not pass; the interruption is for a moment, and is not what is said.
        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.FEATURE);
    }

    /**
     * The exclusion is checked last, so a relay that sits a turn out for what the request carries is not reached by it; the plan
     * still has the session the exclusion stands for, to hand it on to the turn that follows (a chain names its session by the
     * response it continues, which changes with every turn).
     */
    @Test
    void aRelayThatSitsATurnOutForWhatTheRequestCarriesStillHasTheSessionItsExclusionIsHandedOnUnder() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        TierPlan plan = planRemembering(messages(raw.toString()).withSessionName("key-1:session-1"));

        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.FEATURE);
        assertThat(plan.relaySession()).isEqualTo("key-1:session-1");
        // A relay left out for what holds for every request (a closed gate) is nothing of the session's.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "another-key")));
        assertThat(planRemembering(messages(raw.toString()).withSessionName("key-1:session-1")).relaySession()).isNull();
    }

    @Test
    void aRequestThatNothingTellsFromOthersExcludesNothingAndHasNoSessionToWriteUnder() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        TierPlan anonymous = planRemembering(messages(CLAUDE_CODE, (String) null));

        assertThat(anonymous.relaySession()).isNull();
        assertThat(anonymous.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
    }

    @Test
    void aPlanWithoutARelayHasNoSessionForRelays() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "another-key")));

        TierPlan plan = planRemembering(messages(CLAUDE_CODE));

        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.GATE);
        assertThat(plan.relaySession()).isNull();
    }

    @Test
    void theRelaysAreKeptFromTheSessionWhateverThePolicyOfTheVendorIsAboutKeepingSessionsInATier() {
        Member member = relayMember();
        List<MemberModel> models = List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true));
        configure(SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, false, false, false, 16, true, 7L)),
            anthropicTiers(), List.of(member), models, List.of(gate("MESSAGES", "ON"))));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        TierPlan plan = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));

        // No tier binding is read or written for this vendor, and the relay that broke off the answer is not asked again all the same.
        assertThat(plan.session()).isEmpty();
        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
        // And the session a relay of another request is written under exists for the vendor too.
        TierPlan other = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-2"));
        assertThat(other.session()).isEmpty();
        assertThat(other.relaySession()).isEqualTo("key-1:session-2");
    }

    @Test
    void aPinToTheTierOfARelayThatBrokeOffAnAnswerIsKeptWhetherItIsASuffixOrAHint() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        // The relay is out for two minutes, which is not a tier with no account for the model: the pin stays, and the
        // request is answered with SOURCE_TIER_UNAVAILABLE (a retry) where there is nothing to select. A hint is no
        // different: it is not dropped for a tier that can serve again shortly, whatever else is in the tier.
        TierPlan pinned = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1")
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay")));
        assertThat(pinned.pinned()).isTrue();
        assertThat(pinned.levels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(pinned.levels().get(0).relays()).isEmpty();
        assertThat(pinned.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
        for (TierSelectorParser.Selector hint : List.of(TierSelectorParser.fromHeader("relay"), TierSelectorParser.fromPreference("relay"))) {
            TierPlan hinted = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1").withSelector(hint));
            assertThat(hinted.pinned()).isTrue();
            assertThat(hinted.levels()).extracting(Level::tierId).containsExactly("relay");
            assertThat(hinted.levels().get(0).relays()).isEmpty();
        }

        // Two minutes later it is the relay's again.
        nanos.addAndGet(java.time.Duration.ofSeconds(121).toNanos());
        TierPlan later = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1")
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay")));
        assertThat(later.levels().get(0).relay("relay-1")).isPresent();
    }

    @Test
    void aPlanKnowsWhetherNativeAccountsAreInReachForARelayThatRefusesTheRequestItself() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        TierSelectorParser.Selector suffix = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay");
        TierSelectorParser.Selector suffixAndBelow = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay+");
        TierSelectorParser.Selector header = TierSelectorParser.fromHeader("relay");

        // Kiro is in the cache: its accounts take the prompt the relay refused, whatever they are doing for now.
        assertThat(planRemembering(messages(CLAUDE_CODE)).retriesAfterRelayRefusal(true)).isTrue();
        assertThat(planRemembering(messages(CLAUDE_CODE)).retriesAfterRelayRefusal(false)).isTrue();
        usable.remove(kiro);
        assertThat(planRemembering(messages(CLAUDE_CODE)).retriesAfterRelayRefusal(true)).as("out of quota").isTrue();
        // A request that named the tier by the suffix is served by the tiers it names alone.
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(suffix)).retriesAfterRelayRefusal(true)).isFalse();
        usable.add(kiro);
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(suffix)).retriesAfterRelayRefusal(true)).isFalse();
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(suffixAndBelow)).retriesAfterRelayRefusal(true)).isTrue();
        // A hint gives way to the other tiers, for the refusal of a length, which this node learns, and for that alone.
        TierPlan hinted = planRemembering(messages(CLAUDE_CODE).withSelector(header));
        assertThat(hinted.pinned()).isTrue();
        assertThat(hinted.retriesAfterRelayRefusal(true)).isTrue();
        assertThat(hinted.retriesAfterRelayRefusal(false)).isFalse();
        // The hint to tiers that hold Kiro keeps them for the refusal that is not learned, and the retry crosses to Kiro.
        for (TierSelectorParser.Selector range : List.of(TierSelectorParser.fromHeader("relay+"), TierSelectorParser.fromHeader("1+"),
            TierSelectorParser.fromPreference("relay+"))) {
            TierPlan ranged = planRemembering(messages(CLAUDE_CODE).withSelector(range));
            assertThat(ranged.pinned()).isTrue();
            assertThat(ranged.retriesAfterRelayRefusal(true)).isTrue();
            assertThat(ranged.retriesAfterRelayRefusal(false)).isTrue();
        }
        // Without a native account anywhere there is nothing to try again for.
        cached.remove(kiro);
        assertThat(planRemembering(messages(CLAUDE_CODE)).retriesAfterRelayRefusal(true)).isFalse();
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(header)).retriesAfterRelayRefusal(true)).isFalse();
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(TierSelectorParser.fromHeader("relay+")))
            .retriesAfterRelayRefusal(false)).isFalse();
    }

    @Test
    void aPlanWithNoRelayInItHasNothingToRetryAfterARelayRefusal() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "another-key")));

        assertThat(planRemembering(messages(CLAUDE_CODE)).retriesAfterRelayRefusal(true)).isFalse();
    }

    @Test
    void theInterruptionsAndTheWindowsAreCountedAmongTheReasonsRelaysAreLeftOut() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));

        assertThat(counts.snapshot()).containsExactly(Map.entry(new XpSkipCounts.Key("anthropic", "relay", XpSkip.Reason.INTERRUPTED), 1L));
    }

    // ---- a relay that sat the request out is no tier the session was pushed out of (design 0, 4.2, 4.7) ----

    /** Two relays in the first tier, the first with a window of 1,000 tokens and the second with one of 200,000. */
    private void configureRelaysOfDifferentWindows() {
        Member small = relayMember("relay-1", false);
        Member large = relayMember("relay-2", false);
        cached.add(secondRelay);
        usable.add(secondRelay);
        configure(ownerConfigurationWith(List.of(small, large), List.of(
            mapping(small, relay, "claude-sonnet-4.5", "relay-sonnet", 1_000, true),
            mapping(large, secondRelay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gate("MESSAGES", "ON")));
    }

    @Test
    void aTierWhoseLargerRelayRefusedTheRequestIsNoTierTheSessionWasPushedOutOfWhenTheSmallerOneWasLeftOutForItsWindow() {
        configureRelaysOfDifferentWindows();

        TierPlan compaction = planRemembering(messages(conversationOf(4_000)));

        // The tier is in the plan: relay-2 takes part, and relay-1 sat this request out for its window.
        assertThat(compaction.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(compaction.xpSkips()).extracting(XpSkip::accountId, XpSkip::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("relay-1", XpSkip.Reason.CONTEXT));
        Level relays = compaction.levels().get(0);
        Level kiroTier = compaction.levels().get(1);
        // relay-2 is busy: the walk finds the tier with nothing to offer, and Kiro serves. That is the request's doing, as
        // relay-1 would have served a smaller one, and the session is not kept down for it.
        TierWalk walk = new TierWalk(compaction);
        walk.refused(relays, "relay-2", false);
        walk.passed(relays);
        assertThat(walk.realDowngradeTo(kiroTier)).isFalse();
        // The same walk of a request that fits both relays, whose tier is all there, is a fall.
        TierPlan small = planRemembering(messages(conversationOf(100)));
        assertThat(small.xpSkips()).isEmpty();
        TierWalk fell = new TierWalk(small);
        fell.refused(small.levels().get(0), "relay-1", false);
        fell.refused(small.levels().get(0), "relay-2", false);
        fell.passed(small.levels().get(0));
        assertThat(fell.realDowngradeTo(small.levels().get(1))).isTrue();
    }

    @Test
    void aTierWhoseLargerRelayIsOutOfQuotaIsNotFoundUnavailableWhenTheSmallerOneWasLeftOutForTheRequest() {
        configureRelaysOfDifferentWindows();
        usable.remove(secondRelay);

        TierPlan compaction = planRemembering(messages(conversationOf(4_000)));

        // relay-2 cannot serve now and relay-1 sat this request out: the tier is not one the request found empty.
        assertThat(compaction.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(compaction.unavailableTiers()).isEmpty();
        assertThat(compaction.requestBoundAbove(compaction.levels().get(0))).isTrue();
        assertThat(new TierWalk(compaction).realDowngradeTo(compaction.levels().get(0))).isFalse();

        // A tier whose relays all take part and none can serve is: the control.
        usable.remove(relay);
        TierPlan small = planRemembering(messages(conversationOf(100)));
        assertThat(small.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(small.unavailableTiers()).extracting(Tier::tierId).containsExactly("relay");
        assertThat(small.requestBoundAbove(small.levels().get(0))).isFalse();
        assertThat(new TierWalk(small).realDowngradeTo(small.levels().get(0))).isTrue();
    }

    @Test
    void aTierWhoseOtherRelayIsBusyIsNoTierTheSessionWasPushedOutOfWhenARelayBrokeOffAnAnswerForIt() {
        Member first = relayMember("relay-1", false);
        Member second = relayMember("relay-2", false);
        cached.add(secondRelay);
        usable.add(secondRelay);
        configure(ownerConfigurationWith(List.of(first, second), List.of(
            mapping(first, relay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
            mapping(second, secondRelay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");

        TierPlan retry = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));
        TierPlan anotherSession = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-2"));

        assertThat(retry.xpSkips()).extracting(XpSkip::accountId, XpSkip::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("relay-1", XpSkip.Reason.INTERRUPTED));
        TierWalk walk = new TierWalk(retry);
        walk.refused(retry.levels().get(0), "relay-2", false);
        walk.passed(retry.levels().get(0));
        // The interruption is for two minutes: when it is over, the relay serves the session again and Kiro never kept it.
        assertThat(walk.realDowngradeTo(retry.levels().get(1))).isFalse();
        // Another session never met the interruption, and is pushed down when both relays refuse it.
        assertThat(anotherSession.xpSkips()).isEmpty();
        TierWalk other = new TierWalk(anotherSession);
        other.refused(anotherSession.levels().get(0), "relay-1", false);
        other.refused(anotherSession.levels().get(0), "relay-2", false);
        other.passed(anotherSession.levels().get(0));
        assertThat(other.realDowngradeTo(anotherSession.levels().get(1))).isTrue();
    }

    @Test
    void aTierWhoseOtherRelaySawNoImageIsNoTierTheSessionWasPushedOutOfWhenTheRequestCarriesOne() {
        // An openai tier of a relay that sees images and one that does not, and a gpt request with a picture: only the first
        // takes part, and a request without one would be given both.
        Member blind = openAiRelayMember(false, false);
        Member seeing = new Member("openai", "relay-2", "relay", "OPENAI_CHAT", true, true, false, false, false, true, "NONE", 540, true, true);
        cached.add(secondRelay);
        usable.add(secondRelay);
        cached.add(openAiOfficial);
        usable.add(openAiOfficial);
        usable.remove(secondRelay);
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "relay", 1, "relay", "1"),
            tier("openai", "official", 2, "official", "0.5", AccountAllocationCategory.OFFICIAL_API_KEY)
        ), List.of(blind, seeing), List.of(
            mapping(blind, relay, "gpt-5.5", "relay-gpt", 400_000, true),
            mapping(seeing, secondRelay, "gpt-5.5", "relay-gpt", 400_000, true)
        ), List.of(gateOf("openai", "CHAT", "ON"))));
        ObjectNode withImage = chatBody("gpt-5.5");
        withImage.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64,AAAA");

        TierPlan plan = planOpenAiChat(withImage, false);

        assertThat(plan.xpSkips()).extracting(XpSkip::accountId, XpSkip::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("relay-1", XpSkip.Reason.FEATURE));
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(new TierWalk(plan).realDowngradeTo(plan.levels().get(0))).isFalse();
    }

    @Test
    void aRelayWithoutAMappingOrAGateDoesNotHoldTheSessionUpEither() {
        // Relays that no request of the session and model is given are not members of the tier for it: what is left of the
        // tier decides, and the binding of a session is per session and model (design 4.7).
        Member mapped = relayMember("relay-1", false);
        Member unmapped = relayMember("relay-2", false);
        cached.add(secondRelay);
        usable.add(secondRelay);
        usable.remove(relay);
        configure(ownerConfigurationWith(List.of(mapped, unmapped), List.of(
            mapping(mapped, relay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true),
            mapping(unmapped, secondRelay, "claude-opus-4.5", "relay-opus", 200_000, true)), gate("MESSAGES", "ON")));

        TierPlan plan = planRemembering(messages(CLAUDE_CODE));

        assertThat(plan.xpSkips()).extracting(XpSkip::accountId, XpSkip::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("relay-2", XpSkip.Reason.NO_MAPPING));
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("relay");
        assertThat(new TierWalk(plan).realDowngradeTo(plan.levels().get(0))).isTrue();
    }

    @Test
    void aRelayLeftOutForTheRequestAboveTheTierASessionIsBoundToDoesNotHoldItUp() {
        // Three tiers, the relay's first. The session already fell to the second one (official keys), which is out of quota now.
        Member small = relayMember("relay-1", false);
        cached.add(officialAnthropic);
        configure(SourceTierSnapshot.of(7L, List.of(vendor("anthropic")), List.of(
            tier("anthropic", "relay", 1, "relay", "1"),
            tier("anthropic", "official", 2, "official", "0.8", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("anthropic", "kiro", 3, "kiro", "0.6", KIRO, CLAUDE_OAUTH)
        ), List.of(small), List.of(mapping(small, relay, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            List.of(gate("MESSAGES", "ON"))));
        TierPlan unbound = planRemembering(messages(conversationOf(4_000)).withSessionName("key-1:session-1"));
        // Not bound: the relay sat the request out and the official keys are down, so the session is not pushed out of the first
        // tier by it.
        assertThat(unbound.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(unbound.requestBoundAbove(unbound.levels().get(0))).isTrue();
        assertThat(new TierWalk(unbound).realDowngradeTo(unbound.levels().get(0))).isFalse();

        sessions.downgrade(unbound.session().orElseThrow().bindingKey(), new TierSessionBindings.Binding("official", 2), unbound::levelOf);
        TierPlan bound = planRemembering(messages(conversationOf(4_000)).withSessionName("key-1:session-1"));

        // Bound to the official tier, the tier above it is none of this fall's concern: official was found with nothing to
        // serve with, and Kiro serving is the session falling again.
        assertThat(bound.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(bound.unavailableTiers()).extracting(Tier::tierId).containsExactly("official");
        assertThat(bound.requestBoundAbove(bound.levels().get(0))).isFalse();
        assertThat(new TierWalk(bound).realDowngradeTo(bound.levels().get(0))).isTrue();
    }

    @Test
    void theReasonsARelayIsLeftOutForAreSortedIntoThoseOfTheRequestAndThoseOfTheSession() {
        assertThat(java.util.Arrays.stream(XpSkip.Reason.values()).filter(XpSkip.Reason::ofTheRequest))
            .containsExactly(XpSkip.Reason.FEATURE, XpSkip.Reason.CONVERSION, XpSkip.Reason.CONTEXT, XpSkip.Reason.INTERRUPTED);
        assertThat(java.util.Arrays.stream(XpSkip.Reason.values()).filter(XpSkip.Reason::temporary))
            .containsExactly(XpSkip.Reason.INTERRUPTED);
    }

    // ---- a prompt no source of the tiers takes is told what a source that refuses it would tell (design 3, 7.8, 7.9) ----

    private static void assertPromptTooLong(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
            assertThat(refused.getMessage()).isEqualTo(tech.starryapi.platform.xproto.RelayErrorException.PROMPT_TOO_LONG);
        });
    }

    @Test
    void aPromptBeyondTheWindowOfTheOnlyRelayIsTooLongWhenTheTierIsPinnedBySuffix() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));
        TierSelectorParser.Selector pin = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay");

        // The window the administrator wrote, and then the one a refusal showed: the same answer, which is also what the
        // relay's own refusal ends in when there is nothing below to cross to.
        assertThatThrownBy(() -> planRemembering(messages(conversationOf(4_000)).withSelector(pin)))
            .satisfies(TierPlannerXpTest::assertPromptTooLong);
        Relay served = planRemembering(messages(CLAUDE_CODE).withSelector(pin)).levels().get(0).relay("relay-1").orElseThrow();
        limits.learn(served.mapping(), served.estimatedInput());
        assertThatThrownBy(() -> planRemembering(messages(CLAUDE_CODE).withSelector(pin)))
            .satisfies(TierPlannerXpTest::assertPromptTooLong);
    }

    @Test
    void aPromptBeyondTheWindowOfTheOnlyRelayIsTooLongWhenNoNativeAccountIsLeftToServeIt() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        Relay served = planRemembering(messages(CLAUDE_CODE)).levels().get(0).relay("relay-1").orElseThrow();
        limits.learn(served.mapping(), served.estimatedInput());
        cached.remove(kiro);
        usable.remove(kiro);

        assertThatThrownBy(() -> planRemembering(messages(CLAUDE_CODE)))
            .satisfies(TierPlannerXpTest::assertPromptTooLong);
        // A hint for the relay's tier is dropped to the automatic request, which is told the same.
        assertThatThrownBy(() -> planRemembering(messages(CLAUDE_CODE).withSelector(TierSelectorParser.fromHeader("relay"))))
            .satisfies(TierPlannerXpTest::assertPromptTooLong);
    }

    @Test
    void aPromptBeyondTheWindowIsServedByTheTierBelowWhenThereIsOneAndIsNoReasonToRefuseIt() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));

        TierPlan automatic = planRemembering(messages(conversationOf(4_000)));
        TierPlan orBelow = planRemembering(messages(conversationOf(4_000))
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay+")));

        assertThat(automatic.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(orBelow.levels()).extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aFeatureOrAnInterruptionOfAnotherRelayComesBeforeTheLengthOfThePrompt() {
        Member small = relayMember("relay-1", false);
        Member plain = relayMember("relay-2", false);
        cached.add(secondRelay);
        usable.add(secondRelay);
        configure(ownerConfigurationWith(List.of(small, plain), List.of(
            mapping(small, relay, "claude-sonnet-4.5", "relay-sonnet", 1_000, true),
            mapping(plain, secondRelay, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gate("MESSAGES", "ON")));
        TierSelectorParser.Selector pin = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay");
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-2");

        // relay-1 is too small for the prompt and relay-2 is out for two minutes: not a request to refuse for good.
        TierPlan plan = planRemembering(messages(conversationOf(4_000)).withSessionName("key-1:session-1").withSelector(pin));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(plan.levels().get(0).relays()).isEmpty();
        assertThat(plan.xpSkips()).extracting(XpSkip::reason)
            .containsExactlyInAnyOrder(XpSkip.Reason.CONTEXT, XpSkip.Reason.INTERRUPTED);
    }

    @Test
    void aRelayThatBrokeOffAnAnswerIsOnlySaidToBeOutForAMomentForARequestItWouldOtherwiseBeGiven() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");
        ObjectNode nameless = (ObjectNode) json(CLAUDE_CODE);
        nameless.withArray("tools").addObject().put("name", "").put("description", "nameless");

        TierPlan large = planRemembering(messages(conversationOf(4_000)).withSessionName("key-1:session-1"));
        TierPlan unconvertible = planRemembering(messages(nameless.toString()).withSessionName("key-1:session-1"));
        TierPlan small = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1"));

        // The window and the conversion are the request's, and hold whatever the session met on the relay.
        assertThat(large.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONTEXT);
        assertThat(unconvertible.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONVERSION);
        assertThat(small.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
        assertThat(counts.snapshot()).containsOnly(
            Map.entry(new XpSkipCounts.Key("anthropic", "relay", XpSkip.Reason.CONTEXT), 1L),
            Map.entry(new XpSkipCounts.Key("anthropic", "relay", XpSkip.Reason.CONVERSION), 1L),
            Map.entry(new XpSkipCounts.Key("anthropic", "relay", XpSkip.Reason.INTERRUPTED), 1L));
    }

    @Test
    void aPinToTheRelayTierIsKeptForTheInterruptionOfARequestTheRelayWouldHaveTakenAndNotForOneItWouldNot() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));
        sessions.excludeRelay("anthropic", "key-1:session-1", "relay-1");
        TierSelectorParser.Selector suffix = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay");

        for (TierSelectorParser.Selector hint : List.of(TierSelectorParser.fromHeader("relay"), TierSelectorParser.fromPreference("relay"))) {
            // Beyond the window the relay would not have taken it: the hint gives way, and Kiro serves it as it would have.
            TierPlan large = planRemembering(messages(conversationOf(4_000)).withSessionName("key-1:session-1").withSelector(hint));
            assertThat(large.pinned()).isFalse();
            assertThat(large.levels()).extracting(Level::tierId).containsExactly("kiro");
            assertThat(large.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, TierResponseHeaders.IGNORED);
            TierPlan small = planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1").withSelector(hint));
            assertThat(small.pinned()).isTrue();
            assertThat(small.levels()).extracting(Level::tierId).containsExactly("relay");
        }
        // The suffix is refused for the length of the prompt, as it is when the relay is not out for a moment.
        assertThatThrownBy(() -> planRemembering(messages(conversationOf(4_000)).withSessionName("key-1:session-1").withSelector(suffix)))
            .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessage()).startsWith("prompt is too long"));
        assertThat(planRemembering(messages(CLAUDE_CODE).withSessionName("key-1:session-1").withSelector(suffix)).pinned()).isTrue();
    }

    // ---- a relay that is switched off keeps a request to its tier only when it could have served the request ----

    @Test
    void aHintForTheRelayTierWhoseRelayIsSwitchedOffIsDroppedForARequestTheRelayCouldNotHaveServedAnyway() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON")));
        cached.remove(relay);
        switchedOff.add(relay);
        ObjectNode search = (ObjectNode) json(CLAUDE_CODE);
        search.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        // Claude Code's small model, which the relay has no mapping for, and its web search sub-request.
        TierPlan haiku = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE.replace("claude-sonnet-4.5", "claude-haiku-4.5"))
            .withSelector(TierSelectorParser.fromHeader("relay")));
        TierPlan webSearch = plan(offering(Status.VERIFIED), messages(search.toString())
            .withSelector(TierSelectorParser.fromHeader("relay")));
        // A request the relay would serve if it were back stays with its tier.
        TierPlan sonnet = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE)
            .withSelector(TierSelectorParser.fromHeader("relay")));

        for (TierPlan plan : List.of(haiku, webSearch)) {
            assertThat(plan.pinned()).isFalse();
            assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
            assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, TierResponseHeaders.IGNORED);
        }
        assertThat(sonnet.pinned()).isTrue();
        assertThat(sonnet.levels()).extracting(Level::tierId).containsExactly("relay");
    }

    @Test
    void aHintForTheRelayTierIsDroppedWhenTheSwitchedOffRelayCouldNotServeBecauseItsMappingIsNotVerifiedOrItsGateIsClosedToTheKey() {
        Member member = relayMember();
        MemberModel unverified = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "relay-sonnet", 200_000, 8192, true, null, null);
        configure(ownerConfiguration(member, List.of(unverified), gate("MESSAGES", "ON")));
        cached.remove(relay);
        switchedOff.add(relay);

        TierPlan notVerified = plan(offering(Status.VERIFIED), messages(CLAUDE_CODE).withSelector(TierSelectorParser.fromHeader("relay")));

        assertThat(notVerified.pinned()).isFalse();
        assertThat(notVerified.levels()).extracting(Level::tierId).containsExactly("kiro");

        configure(ownerConfiguration(member, gate("MESSAGES", "KEYS", "another-key")));
        TierPlan closed = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE).withSelector(TierSelectorParser.fromHeader("relay")));
        assertThat(closed.pinned()).isFalse();
        assertThat(closed.levels()).extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aHintForTheRelayTierWhoseRelayIsSwitchedOffIsDroppedForARequestBeyondItsWindowOrWhatItRefusedAndForOneItCannotConvert() {
        Member member = relayMember();
        configure(ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 1_000, true)),
            gate("MESSAGES", "ON")));
        cached.remove(relay);
        switchedOff.add(relay);
        ObjectNode nameless = (ObjectNode) json(CLAUDE_CODE);
        nameless.withArray("tools").addObject().put("name", "").put("description", "nameless");
        TierSelectorParser.Selector header = TierSelectorParser.fromHeader("relay");

        // The relay would not take these if it were back, so they are not held for it: Kiro serves them in the order of the tiers.
        for (String request : List.of(conversationOf(4_000), nameless.toString())) {
            TierPlan plan = planRemembering(messages(request).withSelector(header));
            assertThat(plan.pinned()).isFalse();
            assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
            assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, TierResponseHeaders.IGNORED);
        }
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(header)).pinned()).isTrue();

        // The same for a prompt the relay was seen to refuse for its length, though its window says it takes it.
        Member roomy = relayMember();
        MemberModel wide = mapping(roomy, "claude-sonnet-4.5", "relay-sonnet", 200_000, true);
        configure(ownerConfiguration(roomy, List.of(wide), gate("MESSAGES", "ON")));
        limits.learn(wide, 3_000);
        TierPlan refused = planRemembering(messages(conversationOf(4_000)).withSelector(header));
        assertThat(refused.pinned()).isFalse();
        assertThat(refused.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(planRemembering(messages(CLAUDE_CODE).withSelector(header)).pinned()).isTrue();
    }

    // ---- what a tier reserves for (design 5.2) ----

    private void configureOpenAiWithAnInlineImage() {
        Member seeing = openAiRelayMember(false, true);
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "relay", 1, "relay", "1"),
            tier("openai", "official", 2, "official", "0.5", AccountAllocationCategory.OFFICIAL_API_KEY)
        ), List.of(seeing), List.of(mapping(seeing, "gpt-5.5", "relay-gpt", 400_000, 5_000, true)),
            List.of(gateOf("openai", "CHAT", "ON"))));
        cached.add(openAiOfficial);
        usable.add(openAiOfficial);
    }

    private ObjectNode gptBodyWithAnInlineImage() {
        ObjectNode body = chatBody("gpt-5.5");
        body.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + "A".repeat(100_000));
        return body;
    }

    private TierPlanRequest openAiChat(ObjectNode body) {
        return new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI, false,
            false, false, true, false, true, body, null, false, "key-1", null)
            .withEntry(TierEntry.CHAT).withEntryFacts(TierEntryFacts.of(TierEntry.CHAT, body));
    }

    @Test
    void aTierOfRelaysAloneIsReservedForByWhatItsRelaysAreSentEvenWhereTheNativeRoutePassesAnInlineImageThrough() {
        configureOpenAiWithAnInlineImage();

        TierPlan auto = plan(offering(Status.VERIFIED), openAiChat(gptBodyWithAnInlineImage()));

        Level relays = auto.levels().get(0);
        Level official = auto.levels().get(1);
        Relay served = relays.relay("relay-1").orElseThrow();
        assertThat(served.inputBound()).isBetween(5_000L, 20_000L);
        // The relay tier holds no native account, so the floor for hidden input that a native cell is bound by is not its bound.
        assertThat(relays.inputBound()).isEqualTo(served.inputBound());
        assertThat(relays.estimate()).isEqualByComparingTo(STANDARD.scaled(BigDecimal.ONE).estimate(served.inputBound(), 4096));
        // The official keys are sent the whole request, inline image included.
        assertThat(official.inputBound()).isGreaterThanOrEqualTo(1_000_000L);
        assertThat(official.estimate()).isEqualByComparingTo(STANDARD.scaled(new BigDecimal("0.5")).estimate(official.inputBound(), 4096));
        assertThat(auto.reservation()).isEqualByComparingTo(official.estimate().max(relays.estimate()));

        TierPlan pinned = plan(offering(Status.VERIFIED), openAiChat(gptBodyWithAnInlineImage())
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay")));
        assertThat(pinned.levels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(pinned.reservation()).isEqualByComparingTo(relays.estimate());
        assertThat(pinned.reservation()).isLessThan(official.estimate().max(relays.estimate()).add(BigDecimal.ONE));
    }

    @Test
    void aTierOfRelaysAloneThatNoRelayCanServeForNowIsReservedForNoMoreThanItsRelaysWouldBeEitherWhenTheRequestIsPinnedToIt() {
        configureOpenAiWithAnInlineImage();
        // The only relay broke off an answer of the session a moment ago and is kept from it for two minutes.
        sessions.excludeRelay("openai", "key-1:session-1", "relay-1");
        BigDecimal floor = STANDARD.scaled(BigDecimal.ONE).estimate(1_000_000L, 4096);

        for (TierSelectorParser.Selector selector : List.of(
            TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay"), TierSelectorParser.fromHeader("relay"))) {
            TierPlan pinned = planRemembering(openAiChat(gptBodyWithAnInlineImage()).withSessionName("key-1:session-1")
                .withSelector(selector));

            // Nobody is asked, and the request is answered with a retry: it needs no more balance than the relay itself did.
            assertThat(pinned.pinned()).isTrue();
            assertThat(pinned.levels()).extracting(Level::tierId).containsExactly("relay");
            assertThat(pinned.levels().get(0).relays()).isEmpty();
            assertThat(pinned.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
            assertThat(pinned.levels().get(0).inputBound()).isZero();
            assertThat(pinned.reservation()).isEqualByComparingTo(STANDARD.scaled(BigDecimal.ONE).estimate(0, 4096));
            assertThat(pinned.reservation()).isLessThan(floor);
        }
        // The same request when the relay is back: its own body is what the tier is reserved for.
        nanos.addAndGet(java.time.Duration.ofSeconds(121).toNanos());
        TierPlan back = planRemembering(openAiChat(gptBodyWithAnInlineImage()).withSessionName("key-1:session-1")
            .withSelector(TierSelectorParser.fromHeader("relay")));
        assertThat(back.levels().get(0).relay("relay-1").orElseThrow().inputBound()).isBetween(5_000L, 20_000L);
        assertThat(back.levels().get(0).inputBound()).isBetween(5_000L, 20_000L);
    }

    @Test
    void aTierThatTheConfigurationCanPutANativeAccountInIsStillReservedForByTheNativeBound() {
        configureOpenAiWithAnInlineImage();
        // The relay's tier lists a category now: an official key may be placed in it.
        Member seeing = openAiRelayMember(false, true);
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "relay", 1, "relay", "1", AccountAllocationCategory.CODEX),
            tier("openai", "official", 2, "official", "0.5", AccountAllocationCategory.OFFICIAL_API_KEY)
        ), List.of(seeing), List.of(mapping(seeing, "gpt-5.5", "relay-gpt", 400_000, 5_000, true)),
            List.of(gateOf("openai", "CHAT", "ON"))));

        TierPlan plan = plan(offering(Status.VERIFIED), openAiChat(gptBodyWithAnInlineImage()));

        assertThat(plan.levels().get(0).inputBound()).isGreaterThanOrEqualTo(1_000_000L);
    }

    @Test
    void theLowestTierAndATierWithANativeMemberRowAreBoundedByTheNativeCellsToo() {
        Member seeing = openAiRelayMember(false, true);
        // The relay's tier is the lowest one, which takes the accounts nothing else places.
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "official", 1, "official", "1", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("openai", "relay", 2, "relay", "0.5")
        ), List.of(seeing), List.of(mapping(seeing, "gpt-5.5", "relay-gpt", 400_000, 5_000, true)),
            List.of(gateOf("openai", "CHAT", "ON"))));
        cached.add(openAiOfficial);
        usable.add(openAiOfficial);

        TierPlan lowest = plan(offering(Status.VERIFIED), openAiChat(gptBodyWithAnInlineImage()));
        assertThat(lowest.levels().get(1).inputBound()).isGreaterThanOrEqualTo(1_000_000L);

        // A tier that is not the lowest and lists nothing, but has an account named for it.
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "relay", 1, "relay", "1"),
            tier("openai", "official", 2, "official", "0.5", AccountAllocationCategory.OFFICIAL_API_KEY)
        ), List.of(seeing, new Member("openai", "openai-official", "relay", null, true, false, false, false, false, true, "NONE", 540, null, null)),
            List.of(mapping(seeing, "gpt-5.5", "relay-gpt", 400_000, 5_000, true)), List.of(gateOf("openai", "CHAT", "ON"))));
        TierPlan named = plan(offering(Status.VERIFIED), openAiChat(gptBodyWithAnInlineImage()));
        assertThat(named.levels().get(0).inputBound()).isGreaterThanOrEqualTo(1_000_000L);
    }

    // ---- who may be on a gate ----

    @Test
    void aCallerWithoutAKeyIsNeverOnTheListOfAGate() {
        // A configuration stored by an earlier release may list the id the callers without a key go by.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "anonymous")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL), messages(CLAUDE_CODE, "anonymous"));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.GATE);
    }

    // ---- reading the request: only where a relay could be asked ----

    @Test
    void theRequestIsReadForRelaysOnlyForTheEntriesAGateIsOpenToARegisteredRelayOfATierAndVendorThatAreOn() {
        DefaultSourceTierPlanner planner = planner(offering(Status.EXPERIMENTAL, Entry.MESSAGES, Entry.CHAT));

        // Registered and not opened yet: the default after a relay is added.
        configure(ownerConfiguration(relayMember()));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "OFF")));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();
        // Opened for the Messages entry only.
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "KEYS", "key-1")));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isTrue();
        assertThat(planner.wantsEntryFacts(TierEntry.CHAT)).isFalse();

        // The vendor, or the tier of the relay, is switched off: nothing is planned over it.
        Member member = relayMember();
        List<MemberModel> models = List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true));
        configure(SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", false, false, false, true, 16, true, 7L)),
            anthropicTiers(), List.of(member), models, List.of(gate("MESSAGES", "ON"))));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();
        configure(SourceTierSnapshot.of(7L, List.of(vendor("anthropic")), List.of(
            new Tier("anthropic", "relay", 1, "relay", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true, false, null),
            tier("anthropic", "kiro", 2, "kiro", "0.6", KIRO, CLAUDE_OAUTH)), List.of(member), models, List.of(gate("MESSAGES", "ON"))));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();

        // A gate of another protocol than the relay's opens nothing for it.
        configure(SourceTierSnapshot.of(7L, List.of(vendor("anthropic")), anthropicTiers(), List.of(member), models,
            List.of(new PairGate("anthropic", "ANTHROPIC_MESSAGES", "MESSAGES", "ON", List.of()))));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();
    }

    // ---- reading the request ----

    @Test
    void theRequestIsReadForRelaysOnlyWhereAPairIsOfferedAndARelayIsRegistered() {
        configure(ownerConfiguration(relayMember(), gate("MESSAGES", "ON"), gate("CHAT", "KEYS", "key-1")));
        DefaultSourceTierPlanner planner = planner(offering(Status.EXPERIMENTAL, Entry.MESSAGES, Entry.CHAT));

        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isTrue();
        assertThat(planner.wantsEntryFacts(TierEntry.CHAT)).isTrue();
        assertThat(planner.wantsEntryFacts(TierEntry.RESPONSES)).isFalse();
        assertThat(planner.wantsEntryFacts(TierEntry.GEMINI)).isFalse();
        assertThat(planner.wantsEntryFacts(null)).isFalse();
        // Nothing offered: no request is read.
        assertThat(planner(NO_PAIR).wantsEntryFacts(TierEntry.MESSAGES)).isFalse();

        configure(ownerConfiguration(null));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();

        when(registry.snapshot()).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TIER_CONFIG_UNAVAILABLE", "not loaded"));
        assertThat(planner.wantsEntryFacts(TierEntry.MESSAGES)).isFalse();
    }

    @Test
    void theFactsOfARequestHoldNothingOfItsBody() {
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image").putObject("source").put("type", "base64").put("media_type", "image/png").put("data", "A".repeat(10_000));

        TierEntryFacts facts = TierEntryFacts.of(TierEntry.MESSAGES, raw);

        assertThat(facts.detected().entry()).isEqualTo(Entry.MESSAGES);
        assertThat(facts.entryDigest().toString()).doesNotContain("AAAA", "messages");
        assertThat(facts.entryDigest().path("thinking").path("type").stringValue()).isEqualTo("adaptive");
        assertThat(facts.entryDigest().path("output_config").path("effort").stringValue()).isEqualTo("medium");
        // The Chat entry's body is its own digest.
        assertThat(TierEntryFacts.of(TierEntry.CHAT, chatBody("claude-sonnet-4.5")).entryDigest()).isNull();
    }

    // ---- the Responses entry (P6-02): Codex, and a chain this node could not expand ----

    private static final PairKey RESPONSES_PAIR = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "anthropic");

    /** A Codex request as the facade will make it: planned over the Chat body of the conversion, the original read for the relays. */
    private TierPlanRequest responses(String codexBody) {
        JsonNode raw = json(codexBody);
        String model = raw.path("model").stringValue();
        return new TierPlanRequest("request-1", model, model, "Kiro", TierRoute.KIRO, false, false, false, true, false, true,
            converter.toChat(raw), null, false, "key-1", null)
            .withEntry(TierEntry.RESPONSES).withEntryFacts(TierEntryFacts.of(TierEntry.RESPONSES, raw));
    }

    private TierPlanRequest responsesOfGpt(String codexBody) {
        JsonNode raw = json(codexBody);
        return new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI, false, false, false, true, false,
            true, converter.toChat(raw), null, false, "key-1", null)
            .withEntry(TierEntry.RESPONSES).withEntryFacts(TierEntryFacts.of(TierEntry.RESPONSES, raw));
    }

    /** The request of Codex for a Claude model, with the chain it continues left in the body as an expansion that failed leaves it. */
    private String codexContinuing(String previousResponseId) {
        return ((ObjectNode) json(CodexRequests.CLAUDE_TURN)).put("previous_response_id", previousResponseId).toString();
    }

    @Test
    void aCodexRequestForAClaudeModelIsPlannedOverTheRelayAndKiro() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL, Entry.RESPONSES), responses(CodexRequests.CLAUDE_TURN));

        // The web search and the namespace that Codex declares in every request do not keep the relay out.
        assertThat(plan.xpSkips()).isEmpty();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        Relay chosen = plan.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(chosen.dispatch().pair()).isEqualTo(RESPONSES_PAIR);
        assertThat(chosen.dispatch().wireModel()).isEqualTo("relay-sonnet");
        assertThat(StrictChatLint.problems(chosen.dispatch().wireBody())).isEmpty();
        // What the relay is not given is recorded for the log, in the words of the policy.
        assertThat(chosen.dispatch().degraded()).containsExactlyInAnyOrder("builtin_tool", "prompt_cache_key");
        assertThat(plan.startLevel().tierId()).isEqualTo("relay");
        assertThat(plan.unavailableTiers()).isEmpty();
    }

    @Test
    void theRelayIsSentTheFunctionsOfCodexAndNothingOfTheRestOfItsRequest() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));

        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(CodexRequests.CLAUDE_TURN));

        JsonNode body = plan.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireBody();
        assertThat(body.path("tools")).extracting(tool -> tool.path("function").path("name").stringValue())
            .containsExactly("exec_command", "update_plan");
        assertThat(body.path("tool_choice").stringValue()).isEqualTo("auto");
        assertThat(body.path("parallel_tool_calls").booleanValue()).isFalse();
        assertThat(body.path("stream").booleanValue()).isTrue();
        assertThat(body.path("messages").get(0).path("role").stringValue()).isEqualTo("system");
        assertThat(body.toString()).doesNotContain("web_search", "multi_agent_v1", "client_metadata", "prompt_cache_key",
            "x-codex-installation-id");
        assertThat(body.has("include")).isFalse();
        assertThat(body.has("store")).isFalse();
    }

    @Test
    void theEditingToolOfCodexIsAskedOfTheRelayAsAFunctionWithOneArgument() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));
        ObjectNode raw = (ObjectNode) json(CodexRequests.CLAUDE_TURN);
        raw.withArray("tools").addObject().put("type", "custom").put("name", "apply_patch").put("description", "Edits files");

        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(raw.toString()));

        assertThat(plan.xpSkips()).isEmpty();
        JsonNode tools = plan.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireBody().path("tools");
        JsonNode patch = null;
        for (JsonNode tool : tools) {
            if ("apply_patch".equals(tool.path("function").path("name").stringValue())) {
                patch = tool;
            }
        }
        assertThat(patch).isNotNull();
        assertThat(patch.path("type").stringValue()).isEqualTo("function");
        assertThat(patch.path("function").path("parameters").path("required").get(0).stringValue()).isEqualTo("patch");
    }

    @Test
    void aCodexRequestForAGptModelIsNotGivenToARelayThatCannotRunItsBuiltInToolsAndIsOnceTheyAreOut() {
        configureOpenAi(openAiRelayMember(false), gateOf("openai", "RESPONSES", "ON"));

        TierPlan full = plan(offering(Status.VERIFIED, Entry.RESPONSES), responsesOfGpt(CodexRequests.GPT_TURN));

        // OpenAI runs the web search and finds the tools of the tool search; a relay cannot, and is not asked to.
        assertThat(full.xpSkips()).hasSize(1);
        assertThat(full.xpSkips().getFirst().reason()).isEqualTo(XpSkip.Reason.FEATURE);
        assertThat(full.xpSkips().getFirst().firstFeature()).isEqualTo("builtin_tool");
        assertThat(full.levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(full.unavailableTiers()).isEmpty();

        ObjectNode raw = (ObjectNode) json(CodexRequests.GPT_TURN);
        ArrayNode kept = mapper.createArrayNode();
        raw.withArray("tools").forEach(tool -> {
            if (!List.of("tool_search", "web_search").contains(tool.path("type").stringValue())) {
                kept.add(tool);
            }
        });
        raw.set("tools", kept);
        TierPlan lean = plan(offering(Status.VERIFIED, Entry.RESPONSES), responsesOfGpt(raw.toString()));

        assertThat(lean.xpSkips()).isEmpty();
        assertThat(lean.levels()).extracting(Level::tierId).containsExactly("relay", "official");
        Relay chosen = lean.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(chosen.dispatch().pair()).isEqualTo(new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai"));
        // The length of the answer is advice and the encrypted reasoning is the OpenAI API's: left out, and said (design 7.9, D6).
        assertThat(chosen.dispatch().degraded()).containsExactlyInAnyOrder("include", "verbosity", "prompt_cache_key", "reasoning_effort");
        assertThat(chosen.dispatch().wireBody().path("tools")).extracting(tool -> tool.path("function").path("name").stringValue())
            .containsExactly("exec_command", "apply_patch");
    }

    /** The plan is made over a Chat body; a request that hands it the Responses body as it came in is not sent to a relay. */
    @Test
    void aResponsesBodyThatWasNotConvertedToChatIsNeverSentToARelay() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));
        JsonNode raw = json(CodexRequests.CLAUDE_TURN);
        TierPlanRequest unconverted = new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Kiro", TierRoute.KIRO,
            false, false, false, true, false, true, raw, null, false, "key-1", null)
            .withEntry(TierEntry.RESPONSES).withEntryFacts(TierEntryFacts.of(TierEntry.RESPONSES, raw));

        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), unconverted);

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONVERSION);
    }

    @Test
    void aReleaseThatOffersNoResponsesPairHasNoRelayTakePartInACodexRequest() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));
        PairRegistry withoutResponses = offering(Status.EXPERIMENTAL, Entry.CHAT, Entry.MESSAGES);

        TierPlan plan = plan(withoutResponses, responses(CodexRequests.CLAUDE_TURN));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).isEmpty();
        assertThat(planner(withoutResponses).wantsEntryFacts(TierEntry.RESPONSES)).isFalse();
        assertThat(planner(offering(Status.EXPERIMENTAL, Entry.RESPONSES)).wantsEntryFacts(TierEntry.RESPONSES)).isTrue();
    }

    @Test
    void thisReleaseOffersTheResponsesPairsSoARelayTakesPartInACodexRequestOfAKeyOnTheList() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));

        TierPlan plan = plan(PairRegistry.defaults(), responses(CodexRequests.CLAUDE_TURN));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        assertThat(planner(PairRegistry.defaults()).wantsEntryFacts(TierEntry.RESPONSES)).isTrue();
    }

    // ---- a chain this node could not expand (design 4.8, 6.3) ----

    @Test
    void aChainThisNodeCouldNotExpandLeavesTheRelayOutAndKiroServesItAsItAlwaysDid() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));

        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(codexContinuing("resp_0123")));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).hasSize(1);
        assertThat(plan.xpSkips().getFirst().reason()).isEqualTo(XpSkip.Reason.FEATURE);
        assertThat(plan.xpSkips().getFirst().firstFeature()).isEqualTo("previous_response_id");
        // The tier the chain does not suit is no tier the session was pushed out of (design 4.7).
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.unavailableAbove(plan.levels().get(0))).isFalse();
        // The same turn of a chain that was expanded is served by the relay.
        assertThat(plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(CodexRequests.CLAUDE_TURN)).levels())
            .extracting(Level::tierId).containsExactly("relay", "kiro");
    }

    @Test
    void aChainThisNodeCouldNotExpandThatNoNativeSourceCanServeIsAConflictAndNotAFeature() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));
        cached.remove(kiro);
        usable.remove(kiro);

        assertThatThrownBy(() -> plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(codexContinuing("resp_0123"))))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("RESPONSE_CHAIN_NOT_PORTABLE");
                assertThat(error.getMessage()).doesNotContain("relay", "relay-1");
            });
        // The same request that continues nothing is served by the relay, and is refused for a feature when it has one.
        assertThat(plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(CodexRequests.CLAUDE_TURN))
            .levels()).extracting(Level::tierId).containsExactly("relay");
        ObjectNode custom = (ObjectNode) json(CodexRequests.CLAUDE_TURN);
        custom.withArray("tools").addObject().put("type", "custom").put("name", "shell");
        assertThatThrownBy(() -> plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(custom.toString())))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_FEATURE_UNSUPPORTED");
                assertThat(error.getDetails()).isEqualTo(Map.of("feature", "custom_tool"));
            });
        // The chain is what is told when the request has a feature besides.
        custom.put("previous_response_id", "resp_0123");
        assertThatThrownBy(() -> plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(custom.toString())))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("RESPONSE_CHAIN_NOT_PORTABLE"));
    }

    /**
     * Context that OpenAI holds and the request only points at is not a chain to continue from the history the client holds,
     * so it is told as a feature of the request; Kiro, which never read it, serves the request as it always did.
     */
    @Test
    void contextTheServerHoldsLeavesTheRelayOutAndIsRefusedByNameWhenOnlyARelayCouldServe() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));
        ObjectNode held = (ObjectNode) json(CodexRequests.CLAUDE_TURN);
        held.put("conversation", "conv_0123456789");

        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(held.toString()));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.FEATURE);
            assertThat(skip.firstFeature()).isEqualTo("server_held_context");
        });
        cached.remove(kiro);
        usable.remove(kiro);
        assertThatThrownBy(() -> plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(held.toString())))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_FEATURE_UNSUPPORTED");
                assertThat(error.getDetails()).isEqualTo(Map.of("feature", "server_held_context"));
            });
    }

    @Test
    void aPinToTheRelayTierIsAnsweredWithAConflictForAChainThisNodeCouldNotExpandAndKiroIsStillServed() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));
        TierPlanRequest chain = responses(codexContinuing("resp_0123"));

        // The suffix names the tier: the request is its or nobody's.
        assertThatThrownBy(() -> plan(offering(Status.VERIFIED, Entry.RESPONSES),
            chain.withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "relay"))))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("RESPONSE_CHAIN_NOT_PORTABLE");
            });
        // A header or a preference is a hint: the tier gives way to the one that can serve the chain.
        TierPlan hinted = plan(offering(Status.VERIFIED, Entry.RESPONSES),
            chain.withSelector(TierSelectorParser.parse(TierSelectorParser.Source.HEADER, "relay")));
        assertThat(hinted.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(hinted.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, TierResponseHeaders.IGNORED);
        // The tier of Kiro can be pinned, and is served.
        assertThat(plan(offering(Status.VERIFIED, Entry.RESPONSES),
            chain.withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "kiro"))).levels())
            .extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aBuiltInToolOfAGptRequestThatOnlyARelayCouldServeIsRefusedWithTheToolNamedAndNeverOnAClaudeRoute() {
        configureOpenAi(openAiRelayMember(false), gateOf("openai", "RESPONSES", "ON"));
        cached.remove(openAiOfficial);
        usable.remove(openAiOfficial);

        assertThatThrownBy(() -> plan(offering(Status.VERIFIED, Entry.RESPONSES), responsesOfGpt(CodexRequests.GPT_TURN)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_FEATURE_UNSUPPORTED");
                assertThat(error.getDetails()).isEqualTo(Map.of("feature", "builtin_tool"));
            });
        // The same tools on the Claude route are left out, as Kiro leaves them out, and the relay serves.
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "ON")));
        cached.remove(kiro);
        usable.remove(kiro);
        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), responses(CodexRequests.CLAUDE_TURN));
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("relay");
        assertThat(plan.levels().get(0).relays()).hasSize(1);
    }

    @Test
    void aLengthOfTheAnswerThatOpenCodeAndCodexSendIsNoReasonToLeaveARelayOut() {
        configureOpenAi(openAiRelayMember(false), gateOf("openai", "RESPONSES", "ON"));
        ObjectNode raw = (ObjectNode) json(CodexRequests.GPT_TURN);
        raw.set("tools", mapper.createArrayNode());
        raw.putObject("text").put("verbosity", "low");

        TierPlan plan = plan(offering(Status.VERIFIED, Entry.RESPONSES), responsesOfGpt(raw.toString()));

        assertThat(plan.xpSkips()).isEmpty();
        assertThat(plan.levels().get(0).relay("relay-1").orElseThrow().dispatch().degraded()).contains("verbosity");
    }

    // ---- the Responses entry planned over its own body (P6-03) ----

    /**
     * A Codex request as the facade plans it: over the Responses body its native accounts are sent (the reservation is sized
     * from it, and the session is told by it), with the conversion that makes the Chat body of a relay.
     */
    private TierPlanRequest responsesOverItsOwnBody(JsonNode raw) {
        String model = raw.path("model").stringValue();
        return new TierPlanRequest("request-1", model, model, "Kiro", TierRoute.KIRO, false, false, false, true, false, true,
            raw, null, false, "key-1", null)
            .withEntry(TierEntry.RESPONSES)
            .withEntryFacts(TierEntryFacts.of(TierEntry.RESPONSES, raw).withChatConversion(converter::toChat));
    }

    @Test
    void aRelayIsGivenTheConversionOfTheResponsesBodyThePlanIsMadeOver() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));
        JsonNode raw = json(CodexRequests.CLAUDE_TURN);

        TierPlan own = plan(offering(Status.EXPERIMENTAL, Entry.RESPONSES), responsesOverItsOwnBody(raw));
        TierPlan converted = plan(offering(Status.EXPERIMENTAL, Entry.RESPONSES), responses(CodexRequests.CLAUDE_TURN));

        assertThat(own.xpSkips()).isEmpty();
        assertThat(own.levels()).extracting(Level::tierId).containsExactly("relay", "kiro");
        // The body of the relay is the same whichever way the plan was handed the request.
        Relay relayOfOwn = own.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(relayOfOwn.dispatch().wireBody()).isEqualTo(converted.levels().get(0).relay("relay-1").orElseThrow().dispatch().wireBody());
        assertThat(relayOfOwn.dispatch().wireBody().path("messages").get(0).path("content").stringValue()).isEqualTo("You are Codex.");
        assertThat(StrictChatLint.problems(relayOfOwn.dispatch().wireBody())).isEmpty();
        assertThat(relayOfOwn.dispatch().degraded()).containsExactlyInAnyOrder("builtin_tool", "prompt_cache_key");
    }

    @Test
    void theNativeTiersAreSizedFromTheResponsesBodyAndNotFromWhatTheConversionKeepsOfIt() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));
        ObjectNode raw = (ObjectNode) json(CodexRequests.CLAUDE_TURN);
        // The history of a session carries the reasoning of the earlier turns, which Kiro is sent as part of the request and a
        // relay is not (it cannot use it): the reservation has to cover what the native accounts are sent.
        raw.withArray("input").addObject().put("type", "reasoning").put("encrypted_content", "x".repeat(200_000));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL, Entry.RESPONSES), responsesOverItsOwnBody(raw));

        Level kiroTier = plan.levels().stream().filter(level -> level.tierId().equals("kiro")).findFirst().orElseThrow();
        assertThat(kiroTier.inputBound()).isEqualTo(RequestBounds.kiroInputTokens(raw));
        assertThat(kiroTier.inputBound()).isGreaterThan(RequestBounds.kiroInputTokens(converter.toChat(raw)) + 150_000);
        // The relay is bounded by the body it is sent, which does not carry the reasoning.
        Relay relayOfOwn = plan.levels().get(0).relay("relay-1").orElseThrow();
        assertThat(relayOfOwn.inputBound()).isLessThan(100_000);
        assertThat(relayOfOwn.dispatch().wireBody().toString()).doesNotContain("xxxx", "reasoning");
    }

    @Test
    void theSessionOfAResponsesRequestIsToldByTheBodyItWasPlannedOver() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));
        JsonNode raw = json(CodexRequests.CLAUDE_TURN);

        TierPlan own = planRemembering(offering(Status.EXPERIMENTAL, Entry.RESPONSES), responsesOverItsOwnBody(raw));

        assertThat(own.relaySession()).isEqualTo(TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", raw));
        assertThat(own.session().orElseThrow().key()).isEqualTo(own.relaySession());
    }

    @Test
    void aConversionThatFailsLeavesTheRelayOutOfThePlanAndNotTheRequest() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));
        JsonNode raw = json(CodexRequests.CLAUDE_TURN);
        TierPlanRequest broken = new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Kiro", TierRoute.KIRO, false,
            false, false, true, false, true, raw, null, false, "key-1", null)
            .withEntry(TierEntry.RESPONSES)
            .withEntryFacts(TierEntryFacts.of(TierEntry.RESPONSES, raw).withChatConversion(body -> {
                throw new IllegalStateException("no conversion today");
            }));

        TierPlan plan = plan(offering(Status.EXPERIMENTAL, Entry.RESPONSES), broken);

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.CONVERSION);
        assertThat(plan.xpSkips().getFirst().detail()).contains("IllegalStateException").doesNotContain("no conversion today");
    }

    @Test
    void aPlanThatKeepsARelayFromTheSessionStillNamesTheSessionSoThatTheExclusionCanFollowTheChain() {
        configure(ownerConfiguration(relayMember(), gate("RESPONSES", "KEYS", "key-1")));
        sessions.excludeRelay("anthropic", "key-1:resp:one", "relay-1");

        TierPlan plan = planRemembering(offering(Status.EXPERIMENTAL, Entry.RESPONSES),
            responsesOverItsOwnBody(json(CodexRequests.CLAUDE_TURN)).withSessionName("key-1:resp:one"));

        assertThat(plan.xpSkips()).extracting(XpSkip::reason).containsExactly(XpSkip.Reason.INTERRUPTED);
        assertThat(plan.levels().get(0).relays()).isEmpty();
        assertThat(plan.relaySession()).isEqualTo("key-1:resp:one");
    }

    // ---- fixtures ----

    private static void assertSamePlan(TierPlan actual, TierPlan expected) {
        assertThat(actual.levels()).extracting(Level::tierRef).containsExactlyElementsOf(expected.levels().stream().map(Level::tierRef).toList());
        for (int i = 0; i < expected.levels().size(); i++) {
            Level a = actual.levels().get(i);
            Level e = expected.levels().get(i);
            assertThat(a.cells()).isEqualTo(e.cells());
            assertThat(a.inputBound()).isEqualTo(e.inputBound());
            assertThat(a.estimate()).isEqualByComparingTo(e.estimate());
            assertThat(a.responseHeaders()).isEqualTo(e.responseHeaders());
        }
        assertThat(actual.holdLevels()).extracting(Level::tierRef).containsExactlyElementsOf(expected.holdLevels().stream().map(Level::tierRef).toList());
        assertThat(actual.reservation()).isEqualByComparingTo(expected.reservation());
        assertThat(actual.startIndex()).isEqualTo(expected.startIndex());
        assertThat(actual.unavailableTiers()).isEqualTo(expected.unavailableTiers());
    }

    /** Tiers that hold native accounts only, the first of the official keys and the second of Kiro. */
    private SourceTierSnapshot nativeConfiguration(List<Member> members, List<MemberModel> models, List<PairGate> gates) {
        return SourceTierSnapshot.of(7L, List.of(vendor("anthropic")), List.of(
            tier("anthropic", "official", 1, "official", "1", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("anthropic", "kiro", 2, "kiro", "0.6", KIRO, CLAUDE_OAUTH)), members, models, gates);
    }

    private Member relayMember(String accountId, boolean vision, String tierId) {
        return new Member("anthropic", accountId, tierId, "OPENAI_CHAT", true, vision, false, false, false, true, "NONE", 540, true, true);
    }

    private PairRegistry offering(Status status) {
        return offering(status, Entry.MESSAGES, Entry.CHAT);
    }

    private PairRegistry offering(Status status, Entry... entries) {
        List<Registration> offered = new ArrayList<>();
        for (Entry entry : entries) {
            for (String vendor : List.of("anthropic", "openai")) {
                offered.add(new Registration(SourceKind.OPENAI_CHAT_RELAY, entry, vendor, status));
            }
        }
        return new PairRegistry(offered);
    }

    private DefaultSourceTierPlanner planner(PairRegistry pairs) {
        return new DefaultSourceTierPlanner(registry, keyResolver, catalog, accountPool, payments, pricing,
            TierSessionBindings.disabled(), true, true, pairs, counts);
    }

    private TierPlan plan(PairRegistry pairs, TierPlanRequest request) {
        return planner(pairs).plan(request).block();
    }

    /** A planner that remembers what the relays did: the interruptions of sessions and the windows their refusals showed. */
    private TierPlan planRemembering(TierPlanRequest request) {
        return planRemembering(offering(Status.VERIFIED), request);
    }

    private TierPlan planRemembering(PairRegistry pairs, TierPlanRequest request) {
        return new DefaultSourceTierPlanner(registry, keyResolver, catalog, accountPool, payments, pricing, sessions, true, true,
            pairs, counts, limits).plan(request).block();
    }

    /** A Claude Code request that opens with a message of {@code words} words, which makes the prompt about as many tokens. */
    private String conversationOf(int words) {
        ObjectNode raw = (ObjectNode) json(CLAUDE_CODE);
        raw.withArray("messages").insertObject(0).put("role", "user").put("content", "word ".repeat(words));
        return raw.toString();
    }

    private static boolean servedByRelay(TierPlan plan) {
        return plan.levels().stream().anyMatch(level -> level.relay("relay-1").isPresent());
    }

    /** A Messages request as the facade makes it: converted for the plan, the original read for the relays. */
    private TierPlanRequest messages(String claudeBody) {
        return messages(claudeBody, "key-1");
    }

    private TierPlanRequest messages(String claudeBody, String keyId) {
        JsonNode raw = json(claudeBody);
        return messages(claudeBody, raw.path("model").stringValue(), keyId);
    }

    private TierPlanRequest messages(String claudeBody, String model, String keyId) {
        JsonNode raw = json(claudeBody);
        ObjectNode chat = adapter.messagesToChat(raw);
        return messagesRequest(model, keyId, chat).withEntryFacts(TierEntryFacts.of(TierEntry.MESSAGES, raw));
    }

    private TierPlanRequest messagesWithoutFacts(String claudeBody) {
        JsonNode raw = json(claudeBody);
        return messagesRequest(raw.path("model").stringValue(), "key-1", adapter.messagesToChat(raw));
    }

    private static TierPlanRequest messagesRequest(String model, String keyId, ObjectNode chat) {
        return new TierPlanRequest("request-1", model, model, "Anthropic", TierRoute.ANTHROPIC, false, false, false, true,
            false, true, chat, null, false, keyId, null).withEntry(TierEntry.MESSAGES);
    }

    /** A Chat Completions request for a Claude model: it takes the Kiro route, as OpenCode's does. */
    private TierPlanRequest chat(ObjectNode body) {
        String model = body.path("model").stringValue();
        return new TierPlanRequest("request-1", model, model, "Kiro", TierRoute.KIRO, false, false, false, true, false, true,
            body, null, false, "key-1", null).withEntry(TierEntry.CHAT).withEntryFacts(TierEntryFacts.of(TierEntry.CHAT, body));
    }

    private ObjectNode chatBody(String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private TierPlan planOpenAiChat(ObjectNode body, boolean fast) {
        TierPlanRequest request = new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI, false,
            false, false, true, fast, true, body, null, false, "key-1", null)
            .withEntry(TierEntry.CHAT).withEntryFacts(TierEntryFacts.of(TierEntry.CHAT, body));
        return plan(offering(Status.VERIFIED), request);
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    private void configure(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private static PairGate gate(String entry, String state, String... keys) {
        return gateOf("anthropic", entry, state, keys);
    }

    private static PairGate gateOf(String vendor, String entry, String state, String... keys) {
        return new PairGate(vendor, "OPENAI_CHAT", entry, state, List.of(keys));
    }

    private static List<Tier> anthropicTiers() {
        return List.of(
            tier("anthropic", "relay", 1, "openai源头", "1"),
            tier("anthropic", "kiro", 2, "kiro反向代理", "0.6", KIRO, CLAUDE_OAUTH)
        );
    }

    private static Tier tier(String vendor, String tierId, int level, String name, String factor, AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = categories.length == 0
            ? EnumSet.noneOf(AccountAllocationCategory.class)
            : EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, name, new BigDecimal(factor), listed, true, true, null);
    }

    /** The relay as the owner registers it: Claude Code's models mapped, a stream with usage, nothing else switched on. */
    private Member relayMember() {
        return relayMember("relay-1", false);
    }

    private Member relayMember(String accountId, boolean vision) {
        return relayMemberWith(accountId, vision, "NONE");
    }

    private Member relayMemberWith(String accountId, boolean vision, String reasoningMode) {
        return new Member("anthropic", accountId, "relay", "OPENAI_CHAT", true, vision, false, false, false, true, reasoningMode, 540,
            true, true);
    }

    private Member openAiRelayMember(boolean priority) {
        return openAiRelayMember(priority, false);
    }

    private Member openAiRelayMember(boolean priority, boolean vision) {
        return new Member("openai", "relay-1", "relay", "OPENAI_CHAT", true, vision, false, priority, false, true, "NONE", 540, true, true);
    }

    private Member openAiRelayMemberWith(boolean priority, boolean vision, String reasoningMode) {
        return new Member("openai", "relay-1", "relay", "OPENAI_CHAT", true, vision, false, priority, false, true, reasoningMode, 540,
            true, true);
    }

    private MemberModel mapping(Member member, String modelId, String wireModel, int window, boolean enabled) {
        return mapping(member, modelId, wireModel, window, 8192, enabled);
    }

    private MemberModel mapping(Member member, String modelId, String wireModel, int window, int imageCap, boolean enabled) {
        return mapping(member, relay, modelId, wireModel, window, imageCap, enabled);
    }

    private MemberModel mapping(Member member, ProxyAccountEntity account, String modelId, String wireModel, int window, boolean enabled) {
        return mapping(member, account, modelId, wireModel, window, 8192, enabled);
    }

    /** A mapping verified for the account as it is now. */
    private MemberModel mapping(
        Member member,
        ProxyAccountEntity account,
        String modelId,
        String wireModel,
        int window,
        int imageCap,
        boolean enabled
    ) {
        MemberModel unverified = new MemberModel(member.vendor(), member.accountId(), modelId, wireModel, window, imageCap, enabled,
            null, null);
        return new MemberModel(member.vendor(), member.accountId(), modelId, wireModel, window, imageCap, enabled,
            java.time.Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, account));
    }

    private SourceTierSnapshot ownerConfiguration(Member member, PairGate... gates) {
        return member == null
            ? ownerConfigurationWith(List.of(), List.of())
            : ownerConfiguration(member, List.of(mapping(member, "claude-sonnet-4.5", "relay-sonnet", 200_000, true)), gates);
    }

    private SourceTierSnapshot ownerConfiguration(Member member, List<MemberModel> models, PairGate... gates) {
        return ownerConfigurationWith(List.of(member), models, gates);
    }

    private SourceTierSnapshot ownerConfigurationWith(List<Member> members, List<MemberModel> models, PairGate... gates) {
        return SourceTierSnapshot.of(7L, List.of(vendor("anthropic")), anthropicTiers(), members, models, List.of(gates));
    }

    private void configureOpenAi(Member member, PairGate... gates) {
        MemberModel gpt = mapping(member, "gpt-5.5", "relay-gpt", 400_000, true);
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "relay", 1, "relay", "1"),
            tier("openai", "official", 2, "official", "0.5", AccountAllocationCategory.OFFICIAL_API_KEY)
        ), List.of(member), List.of(gpt), List.of(gates)));
        if (!cached.contains(openAiOfficial)) {
            cached.add(openAiOfficial);
            usable.add(openAiOfficial);
        }
    }

    private final ProxyAccountEntity openAiOfficial = account("openai-official", "OpenAI", "api-key", null);

    private static VendorConfig vendor(String vendor) {
        return new VendorConfig(vendor, true, false, false, true, 16, true, 7L);
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

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BILLING_MULTIPLIER, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
