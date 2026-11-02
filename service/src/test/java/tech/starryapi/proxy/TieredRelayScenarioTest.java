package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.ProxyAccountRepository;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogCustomization;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.proxy.TieredRelayScenarioRelays.Reply;
import tech.starryapi.proxy.TieredRelayScenarioRelays.Seen;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSelectorParser;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.XpLearnedLimits;
import tech.starryapi.proxy.tier.XpSkip;
import tech.starryapi.proxy.tier.XpSkipCounts;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.sourcetier.SourceTierValidator;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reference scenario from the first request to the last credit (design 1, 4.5, 4.7, 5 and 7): the anthropic vendor with an
 * OpenAI-format relay as its first tier at the standard price and Kiro as its second at 0.6 of it, served to Claude Code
 * (the Messages endpoint, whose route also holds the Anthropic keys) and to OpenCode (the Chat endpoint, whose route is Kiro
 * alone), each by the same scenarios. Everything between the client and the wire is the code of this release: the facade,
 * the real planner over the registry of this release, the real account pool, session bindings and learned limits, the
 * real dispatcher and relay client, and the real response factories. The relay is a server on a loopback port that answers
 * what a scenario scripts (a stream, a JSON body, an error, a stream cut off) and keeps what it was asked. What is stood in
 * for is only what is outside the gateway: Kiro and the Anthropic keys answer per account, admission says who is busy, and
 * a ledger takes the place of the billing functions of the database (their SQL side is
 * {@code TieredRelayScenarioBillingPostgreSqlIntegrationTest}).
 *
 * <p>The requests are the shapes the two clients send (Claude Code 2.1.168: a system array, cache marks, thinking, context
 * management, metadata and a stream; OpenCode 1.18.16: a system message, cache marks, {@code tool_choice} and a stream with
 * usage), cut down to what a scenario needs. The relay's answers are authored from the documented Chat Completions shapes.
 *
 * <p>Every scenario ends on the same promise: what the user is charged is the standard price times the factor of the tier
 * that served, never more than was reserved, and a request that nobody served is released, not left for review.
 */
class TieredRelayScenarioTest {

    private static final String KEY = "key-1";
    private static final String MODEL = "claude-sonnet-4.5";
    private static final String HAIKU = "claude-haiku-4.5";
    /** What every source reports for a request: 800 in and 100 out, 0.0039 at the standard price and 0.00234 at 0.6 of it. */
    private static final PlatformUsage USAGE = new PlatformUsage(
        TieredRelayScenarioRelays.INPUT_TOKENS, TieredRelayScenarioRelays.OUTPUT_TOKENS, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult KIRO_ANSWER = new PlatformCompletionResult("from kiro", USAGE);
    /** $3/M in, $15/M out: the prices of the reference scenario (design 5.5). */
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));
    /** The request headers a relay may be sent, and no others: the client's own are never forwarded (design 7.12). */
    private static final Set<String> RELAY_HEADERS = Set.of(
        "accept", "accept-encoding", "authorization", "connection", "content-length", "content-type", "host", "user-agent");
    /** How the first block of the system prompt of Claude Code starts; the hash at its end is different in every request. */
    private static final String BILLING_HEADER = "x-anthropic-billing-header: cc_version=2.1.168.177; cc_entrypoint=cli; cch=278c4;";
    private static final Pattern MINTED_MESSAGES_ID = Pattern.compile("toolu_[0-9A-Za-z]{24}");
    private static final Pattern MINTED_CALL_ID = Pattern.compile("call_[0-9A-Za-z]{24}");

    /** The one client of relays for all the scenarios: it keeps the connections and threads of the HTTP stack. */
    private static final OpenAiChatRelayClient RELAY_CLIENT =
        new OpenAiChatRelayClient(new ObjectMapper(), ProductionWebClients.builder().build());

    private final ObjectMapper json = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(modelCatalog);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final AccountSettingsService accountSettings = mock(AccountSettingsService.class);
    private final ModelMappingService modelMapping = mock(ModelMappingService.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final AnthropicUpstreamClient anthropicClient = mock(AnthropicUpstreamClient.class);
    private final KiroUpstreamClient kiroClient = mock(KiroUpstreamClient.class);
    private final OpenAiUpstreamClient openAiClient = mock(OpenAiUpstreamClient.class);
    private final OpenAiResponseOwnerStore ownerStore = mock(OpenAiResponseOwnerStore.class);
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal(KEY, "test", null, BigDecimal.ZERO);

    private final AtomicLong clock = new AtomicLong();
    private final ProxyProperties properties = new ProxyProperties();
    private final TierSessionBindings bindings = new TierSessionBindings(properties, clock::get);
    private final XpLearnedLimits learnedLimits = new XpLearnedLimits(clock::get);
    private final XpSkipCounts skipCounts = new XpSkipCounts();
    private final Ledger ledger = new Ledger();

    /** Every dispatch, the relays' and the native accounts', in the order the sources were asked. */
    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    /** What each native account answers; an account that is not listed answers {@link #KIRO_ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> nativeAnswers = new HashMap<>();
    /** What the native accounts were sent, by account, in order: the tool ids the history of a request carries. */
    private final List<NativeCall> nativeCalls = new CopyOnWriteArrayList<>();
    /** The accounts admission turns away as busy. */
    private final Set<String> busy = new HashSet<>();
    private final List<TierPlan> plans = new CopyOnWriteArrayList<>();
    private final List<StatsService.RequestLog> logs = new CopyOnWriteArrayList<>();

    /** The headers a client is configured to send with every request, such as the one that names a tier. */
    private final Map<String, String> extraHeaders = new HashMap<>();
    private TieredRelayScenarioRelays relays;
    private ProxyFacade facade;
    private AccountPoolService accountPool;
    /** The exchange of the latest request, whose response is where the gateway puts the Retry-After of a refusal. */
    private MockServerWebExchange lastExchange;

    @BeforeEach
    void standInForTheEdges() throws IOException {
        relays = new TieredRelayScenarioRelays(dispatched::add);
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            ledger.reserved.add(new Reserved(invocation.getArgument(2), json.readTree((String) invocation.getArgument(3))));
            return Mono.empty();
        });
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenAnswer(invocation -> {
            ledger.plainDispatches++;
            return Mono.empty();
        });
        ApiKeyService.DispatchLease lease = new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenAnswer(invocation -> {
            ledger.leasedTo.add(invocation.getArgument(2));
            return Mono.just(lease);
        });
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            ledger.leasedTo.add(invocation.getArgument(2));
            return Mono.just(lease);
        });
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            ledger.settled.add(new Settled(invocation.getArgument(2), null, null, null));
            return Mono.empty();
        });
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenAnswer(invocation -> {
                ledger.settled.add(new Settled(invocation.getArgument(2), invocation.getArgument(5), invocation.getArgument(6),
                    invocation.getArgument(7)));
                return Mono.empty();
            });
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenAnswer(invocation -> {
            ledger.released.add(new Released(invocation.getArgument(2), invocation.getArgument(3)));
            return Mono.empty();
        });
        when(rateLimiter.check(KEY)).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(RoutingPolicySettings.defaults()));
        when(statsService.log(any())).thenAnswer(invocation -> {
            logs.add(invocation.getArgument(0));
            return Mono.empty();
        });
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AccountAdmissionStore.AdmissionRequest request = invocation.getArgument(0);
            return Mono.just(busy.contains(request.accountId())
                ? new AdmissionDecision(false, null, "ACCOUNT_BUSY", null, null)
                : new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600)));
        });
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatchNative(invocation.getArgument(0), invocation.getArgument(1)));
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatchNative(invocation.getArgument(0), invocation.getArgument(1)));
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatchNative(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @AfterEach
    void stopTheRelays() {
        relays.close();
    }

    // ---- the relay serves ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void theRelayServesTheRequestAndIsChargedThePriceOfItsTier(Client client) {
        serve(new Setup());

        Turn turn = ask(client, first(client, MODEL, "Run echo hello with the Bash tool"), "session-1");

        assertThat(turn.answer().text()).isEqualTo("from relay-1");
        assertServedBy(turn.response(), "relay", "1");
        assertThat(dispatched).containsExactly("relay-1");
        ledger.assertCharged("anthropic:relay", 1, "1");
        // The reservation was sized for the dearer of the two tiers that could have served, and everything above the
        // price of the one that did went back.
        assertThat(ledger.holdLevels()).containsExactly("anthropic:relay", "anthropic:kiro");
        assertThat(ledger.settled.getFirst().credits()).isLessThan(ledger.reservedCredits());
        assertThat(ledger.leasedTo).containsExactly("anthropic:relay");
        assertThat(ledger.plainDispatches).isZero();
        assertThat(ledger.released).isEmpty();
        assertThat(logs).singleElement().satisfies(row -> {
            assertThat(row.success()).isTrue();
            assertThat(row.accountId()).isEqualTo("relay-1");
            assertThat(row.sourceTierRef()).isEqualTo("anthropic:relay");
            assertThat(row.sourceTierLevel()).isEqualTo(1);
            assertThat(row.priceFactor()).isEqualByComparingTo("1");
            assertThat(row.sourceDispatch()).isEqualTo("OPENAI_CHAT");
            assertThat(row.wireModel()).isEqualTo("relay-sonnet");
            assertThat(row.usageSource()).isEqualTo("REPORTED");
            // What the row says of the model and the path is what it always said.
            assertThat(row.model()).isEqualTo(MODEL);
            assertThat(row.path()).isEqualTo(client.path());
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void theRelayIsSentOnlyWhatItsMappingAndSettingsAllowAndNothingOfTheClient(Client client) {
        serve(new Setup());

        ask(client, first(client, MODEL, "Run echo hello with the Bash tool"), "session-1");

        Seen seen = relays.seen().getFirst();
        JsonNode sent = seen.body();
        // The name of the mapping, not the client's; a stream with the usage block, as the member is set to.
        assertThat(sent.path("model").asString()).isEqualTo("relay-sonnet");
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(sent.path("messages").isArray()).isTrue();
        assertThat(sent.path("tools").isArray()).isTrue();
        // Thinking, metadata, context management and cache marks are the client's business with Anthropic, not the relay's.
        for (String name : List.of("thinking", "metadata", "context_management", "system", "user", "store", "output_config")) {
            assertThat(sent.has(name)).as("the relay is not sent %s", name).isFalse();
        }
        assertThat(sent.toString()).doesNotContain("cache_control", "device_id", "session_id", "context_management");
        // Its own key and the content headers: not one header of the client, nor the client's session.
        assertThat(seen.headers()).containsEntry("authorization", "Bearer sk-relay-1");
        assertThat(RELAY_HEADERS).containsAll(seen.headers().keySet());
        assertThat(seen.headers().values().toString()).doesNotContain("session-1", KEY);
    }

    /**
     * The first system block of Claude Code changes with every request, which keeps a relay from caching the start of the
     * prompt: the relay is not sent it. Kiro, which the same request falls to, is sent what it always was.
     */
    @Test
    void theBillingHeaderOfClaudeCodeIsLeftOutOfWhatTheRelayIsSentAndNotOfWhatKiroIsSent() {
        serve(new Setup());
        ObjectNode request = first(Client.CLAUDE_CODE, MODEL, "hello");
        assertThat(request.path("system").get(0).path("text").asString()).isEqualTo(BILLING_HEADER);

        ask(Client.CLAUDE_CODE, request, "session-1");

        JsonNode sent = relays.seen().getFirst().body();
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(sent.path("messages").get(0).path("content").asString())
            .isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
        assertThat(sent.toString()).doesNotContain("x-anthropic-billing-header", "cch=");
        assertThat(logs).singleElement().satisfies(row -> assertThat(row.degradedFeatures()).contains("billing_header_dropped"));

        // The same request, once the relay cannot serve it, is Kiro's: with the header in its system message, as before.
        relays.always("relay-1", Reply.failure(404, "model_not_found"));
        ask(Client.CLAUDE_CODE, first(Client.CLAUDE_CODE, MODEL, "hello again"), "session-2");
        assertThat(nativeCalls).singleElement().satisfies(call -> {
            assertThat(call.account()).isEqualTo("kiro-1");
            assertThat(call.payload()).contains(BILLING_HEADER);
        });
    }

    @Test
    void theRequestOfOpenCodeHasNoHeaderToLeaveOutAndIsNotGivenTheRepair() {
        serve(new Setup());

        ask(Client.OPENCODE, first(Client.OPENCODE, MODEL, "hello"), "session-1");

        assertThat(logs).singleElement().satisfies(row -> assertThat(row.degradedFeatures()).doesNotContain("billing_header_dropped"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aClientThatDoesNotStreamIsServedByTheRelayToo(Client client) {
        serve(new Setup());
        ObjectNode request = first(client, MODEL, "hello");
        request.put("stream", false);

        Turn turn = ask(client, request, "session-1");

        assertThat(turn.response().getBody()).isInstanceOf(JsonNode.class);
        assertThat(turn.answer().text()).isEqualTo("from relay-1");
        assertServedBy(turn.response(), "relay", "1");
        ledger.assertCharged("anthropic:relay", 1, "1");
    }

    // ---- the relay cannot serve, and the request goes to Kiro (design 4.2, 4.5) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatDoesNotServeTheModelLeavesTheRequestToKiro(Client client) {
        serve(new Setup());
        relays.always("relay-1", Reply.failure(404, "model_not_found"));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        assertThat(turn.answer().text()).isEqualTo("from kiro");
        assertKiroServedAfterRelays(turn, 1);
        // The attempt that failed is logged at its own tier, with how it was sent and why it stopped.
        assertThat(logs).hasSize(2);
        assertThat(logs.getFirst().success()).isFalse();
        assertThat(logs.getFirst().sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(logs.getFirst().sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(logs.getFirst().stopReason()).isEqualTo("MODEL_NOT_SERVED");
        assertThat(logs.getLast().sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(logs.getLast().priceFactor()).isEqualByComparingTo("0.6");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void anErrorInsideAnAnswerThatWasSuccessfulOnTheWireLeavesTheRequestToKiro(Client client) {
        serve(new Setup());
        relays.always("relay-1", Reply.inBand("model_not_found", "no available channel for model " + MODEL));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        assertThat(turn.answer().text()).isEqualTo("from kiro");
        assertKiroServedAfterRelays(turn, 1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aBusyRelayLeavesTheRequestToKiroWithoutBeingAsked(Client client) {
        serve(new Setup());
        busy.add("relay-1");

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        assertThat(turn.answer().text()).isEqualTo("from kiro");
        assertKiroServedAfterRelays(turn, 0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aStreamThatEndsBeforeAnythingWasGeneratedLeavesTheRequestToKiro(Client client) {
        serve(new Setup());
        relays.always("relay-1", Reply.cutBeforeAnything());

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        // Nothing came of it, so nothing was generated: another source may answer, and the user is charged for one.
        assertThat(turn.answer().text()).isEqualTo("from kiro");
        assertKiroServedAfterRelays(turn, 1);
    }

    // ---- the relay turns its own key down ----

    /** How a relay says it will not take the key of its account: a status of 401 or 403, and the same as an error inside a 200. */
    private record Rejection(String name, Reply reply, int status) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> clientsAndRejections() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(
                new Rejection("401", Reply.failure(401, "invalid_api_key"), 401),
                new Rejection("403", Reply.failure(403, "permission_error"), 403),
                new Rejection("an authentication error inside a 200",
                    Reply.inBand("authentication_error", "Incorrect API key provided"), 401),
                new Rejection("a permission error inside a 200",
                    Reply.inBand("permission_error", "Your key may not use this endpoint"), 403))
            .map(rejection -> Arguments.of(client, rejection)));
    }

    private AccountAdmissionStore.AdmissionResult theFailureAdmissionWasToldOf() {
        ArgumentCaptor<AccountAdmissionStore.AdmissionResult> results = ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionResult.class);
        verify(admissionStore, org.mockito.Mockito.atLeastOnce()).reconcile(results.capture());
        return results.getAllValues().stream().filter(result -> !result.success()).findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("clientsAndRejections")
    void aRelayThatRejectsItsOwnKeyLeavesTheRequestToKiroAndKiroIsWhatIsCharged(Client client, Rejection rejection) {
        serve(new Setup());
        relays.always("relay-1", rejection.reply());

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        // The key is the operator's to mend and the user did nothing wrong: the tier below answers, at the price of that tier.
        assertThat(turn.answer().text()).isEqualTo("from kiro");
        assertKiroServedAfterRelays(turn, 1);
        assertThat(logs).hasSize(2);
        assertThat(logs.getFirst().success()).isFalse();
        assertThat(logs.getFirst().sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(logs.getFirst().sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(logs.getFirst().statusCode()).isEqualTo(rejection.status());
        assertThat(logs.getFirst().stopReason()).isEqualTo("KEY_REFUSED");
        assertThat(logs.getFirst().errorMessage()).doesNotContain("relay.internal.example.com", "Incorrect API key");
        assertThat(logs.getLast().sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(logs.getLast().priceFactor()).isEqualByComparingTo("0.6");
        // The relay account rests for ten minutes, as a native account does for credentials that are bad, and is not disabled.
        AccountAdmissionStore.AdmissionResult failed = theFailureAdmissionWasToldOf();
        assertThat(failed.failureScope()).isEqualTo(AccountFailureScope.CREDENTIAL);
        assertThat(failed.cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(failed.permanentCredentialFailure()).isFalse();
        assertThat(failed.statusCode()).isEqualTo(rejection.status());

        // The tier above had nothing left for the request, as it has none for a request whose account is busy: a real fall,
        // so the session stays with Kiro until it has been idle, and does not meet the relay's rejection again at once.
        relays.forget();
        dispatched.clear();
        Turn next = ask(client, first(client, MODEL, "hello again"), "session-1");
        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(next.response(), "kiro", "2");
        assertThat(ledger.holdLevels()).containsExactly("anthropic:kiro");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:kiro"));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("clientsAndRejections")
    void aRelayThatRejectsItsOwnKeyLeavesTheTierToItsNextRelayBeforeTheNextTier(Client client, Rejection rejection) {
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.always("relay-1", rejection.reply());

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(turn.answer().text()).isEqualTo("from relay-2");
        assertServedBy(turn.response(), "relay", "1");
        ledger.assertCharged("anthropic:relay", 1, "1");
        assertThat(ledger.released).isEmpty();
        // The first tier still had a relay, so nobody was pushed out of it: the session's next request starts there again.
        relays.forget();
        dispatched.clear();
        assertServedBy(ask(client, first(client, MODEL, "hello again"), "session-1").response(), "relay", "1");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void whenBothRelaysRejectTheirKeysTheReservedSlotTakesTheRequestToKiro(Client client) {
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.always("relay-1", Reply.failure(401, "invalid_api_key"));
        relays.always("relay-2", Reply.failure(403, "permission_error"));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        assertThat(dispatched).containsExactly("relay-1", "relay-2", "kiro-1");
        assertServedBy(turn.response(), "kiro", "2");
        ledger.assertCharged("anthropic:kiro", 2, "0.6");
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatRejectsItsKeyWithNothingNativeBelowIsNeverToldToTheClient(Client client) {
        serve(new Setup().natives());
        relays.always("relay-1", Reply.failure(401, "invalid_api_key"));

        ApiException refused = refusedWith(client, first(client, MODEL, "hello"), "session-1");

        // What a request nothing can serve is told, and not that the user's key is wrong, which Claude Code reads it as.
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(refused.getMessage()).doesNotContain("Source error", "401");
        assertThat(dispatched).containsExactly("relay-1");
        // The relay had the request and refused it: the reservation is released for good, nothing is left for review.
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatRejectsItsKeyWhileKiroIsOnlyBusyIsNeverToldToTheClientEither(Client client) {
        serve(new Setup());
        busy.add("kiro-1");
        relays.always("relay-1", Reply.failure(403, "permission_error"));

        ApiException refused = refusedWith(client, first(client, MODEL, "hello"), "session-1");

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getMessage()).doesNotContain("Source error", "403");
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());

        // The client's retry is served as soon as Kiro is free.
        busy.clear();
        assertServedBy(ask(client, first(client, MODEL, "hello"), "session-1").response(), "kiro", "2");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void withoutTheReservedSlotTwoRelaysThatRejectTheirKeysEndTheRequestWithoutTheirWords(Client client) {
        serve(new Setup().relays("relay-1", "relay-2").reservedSlot(false));
        relays.always("relay-1", Reply.failure(401, "invalid_api_key"));
        relays.always("relay-2", Reply.failure(401, "invalid_api_key"));

        ApiException refused = refusedWith(client, first(client, MODEL, "hello"), "session-1");

        // The budget of the route is spent on the two relays, and Kiro is not asked: that is the budget's doing, the key's
        // is not what the client is told.
        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatRejectsItsKeyForARequestPinnedToItsTierIsToldToTryAgainWithoutKiroBeingAsked(Client client) {
        serve(new Setup());
        relays.always("relay-1", Reply.failure(401, "invalid_api_key"));

        ApiException refused = refusedWith(client, pinned(first(client, MODEL, "hello"), "relay"), "session-1");

        assertUnavailableToTryAgain(refused);
        assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(refused.getMessage()).doesNotContain("Source error", "401");
        assertThat(dispatched).as("the request is held to the tier it named").containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("clientsAndHints")
    void aRelayThatRejectsItsKeyForARequestThatAHintPinnedIsServedByTheTierBelowWhenTheRangeHasOne(Client client, Hint hint) {
        serve(new Setup());
        nameTheTiersBy(hint);
        relays.always("relay-1", Reply.failure(401, "invalid_api_key"));

        if (hint.holdsKiro) {
            Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");
            assertServedBy(turn.response(), "kiro", "2", "pinned");
            assertThat(dispatched).containsExactly("relay-1", "kiro-1");
            ledger.assertCharged("anthropic:kiro", 2, "0.6");
        } else {
            ApiException refused = refusedWith(client, first(client, MODEL, "hello"), "session-1");
            assertUnavailableToTryAgain(refused);
            assertThat(dispatched).containsExactly("relay-1");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aNativeAccountThatRejectsTheRequestIsStillToldSoAsItAlwaysWas(Client client) {
        serve(new Setup());
        busy.add("relay-1");
        nativeAnswers.put("kiro-1", () -> Mono.error(new UpstreamException(401, "The bearer token is invalid", false)));

        ApiException refused = refusedWith(client, first(client, MODEL, "hello"), "session-1");

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    // ---- two relays in the first tier, and a budget of two accounts (design 4.5, rule 2) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aFailureOfTheFirstRelayIsRetriedOnTheSecondRelayBeforeAnythingBelowIsAsked(Client client) {
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.always("relay-1", Reply.failure(404, "model_not_found"));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        // The tier is used up before the next one is: the second relay serves, and Kiro is never asked.
        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(turn.answer().text()).isEqualTo("from relay-2");
        assertServedBy(turn.response(), "relay", "1");
        ledger.assertCharged("anthropic:relay", 1, "1");
        assertThat(ledger.leasedTo).containsExactly("anthropic:relay", "anthropic:relay");
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void whenBothRelaysFailTheReservedSlotTakesTheRequestToKiroAndTheSessionStaysThere(Client client) {
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.always("relay-1", Reply.failure(404, "model_not_found"));
        relays.always("relay-2", Reply.failure(404, "model_not_found"));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        // The route allows two accounts and both were relays that did not generate: the slot kept for crossing tiers takes
        // the request to Kiro, a third dispatch that is no more than one source generating.
        assertThat(dispatched).containsExactly("relay-1", "relay-2", "kiro-1");
        assertServedBy(turn.response(), "kiro", "2");
        ledger.assertCharged("anthropic:kiro", 2, "0.6");
        assertThat(ledger.leasedTo).containsExactly("anthropic:relay", "anthropic:relay", "anthropic:kiro");
        assertThat(ledger.released).isEmpty();
        assertThat(logs).extracting(StatsService.RequestLog::success, StatsService.RequestLog::sourceTierRef)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(false, "anthropic:relay"),
                org.assertj.core.groups.Tuple.tuple(false, "anthropic:relay"), org.assertj.core.groups.Tuple.tuple(true, "anthropic:kiro"));

        // The first tier was used up, which is a real fall: the session stays below while the relays recover.
        relays.forget();
        dispatched.clear();
        Turn next = ask(client, first(client, MODEL, "hello again"), "session-1");
        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(next.response(), "kiro", "2");
        assertThat(ledger.holdLevels()).as("what the second request reserves for").containsExactly("anthropic:kiro");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:kiro"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aCrossingOnTheReservedSlotWithARelayLeftUntriedIsNotAFallOfTheSession(Client client) {
        serve(new Setup().relays("relay-1", "relay-2", "relay-3"));
        relays.always("relay-1", Reply.failure(404, "model_not_found"));
        relays.always("relay-2", Reply.failure(404, "model_not_found"));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        // The budget of two accounts went on the first two relays, and the slot kept for crossing took the request to Kiro
        // with the third relay never asked.
        assertThat(dispatched).containsExactly("relay-1", "relay-2", "kiro-1");
        assertServedBy(turn.response(), "kiro", "2");
        ledger.assertCharged("anthropic:kiro", 2, "0.6");

        // The first tier was not used up, so the session did not fall (design 4.5, rule 2): its next request starts there.
        relays.forget();
        dispatched.clear();
        Turn next = ask(client, first(client, MODEL, "hello again"), "session-1");
        assertServedBy(next.response(), "relay", "1");
        assertThat(dispatched).singleElement().asString().startsWith("relay-");
        assertThat(ledger.holdLevels()).as("what the second request reserves for").containsExactly("anthropic:relay", "anthropic:kiro");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:relay"));
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void withoutTheReservedSlotTwoFailingRelaysEndTheRequestAndNothingIsHeld(Client client) {
        serve(new Setup().relays("relay-1", "relay-2").reservedSlot(false));
        relays.always("relay-1", Reply.failure(404, "model_not_found"));
        relays.always("relay-2", Reply.failure(404, "model_not_found"));

        assertThatThrownBy(() -> ask(client, first(client, MODEL, "hello"), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().is4xxClientError()).isTrue());

        // Without the slot the budget of the route is what it is today: the request ends with the source's error.
        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
    }

    // ---- the relay may have generated (design 7.7, 7.8, invariant I7) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aStreamThatBreaksOffAfterItStartedIsAskedNowhereElseAndNobodyIsCharged(Client client) {
        // A second relay and Kiro are on offer, and neither is asked: the first one may be generating the answer still.
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.always("relay-1", Reply.cutAfterStart("I will run the"));

        assertThatThrownBy(() -> ask(client, first(client, MODEL, "hello"), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(dispatched).containsExactly("relay-1");
        // The operator carries the cost: the reservation is released for good, and not left for a review.
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
        assertThat(ledger.settled).isEmpty();
        assertThat(logs).singleElement().satisfies(row -> {
            assertThat(row.success()).isFalse();
            assertThat(row.sourceDispatch()).isEqualTo("OPENAI_CHAT");
            assertThat(row.stopReason()).isEqualTo("INTERRUPTED");
        });
    }

    /**
     * The operator carries the cost of an interrupted attempt even when the relay had counted the tokens before it broke
     * off. What it reported is not a reason to charge the user for an answer that was never completed.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aStreamThatBrokeOffAfterTheRelayReportedUsageIsReleasedWithNoChargeToTheUser(Client client) {
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.always("relay-1", Reply.cutAfterUsage("I will run the", 5_000, 700));

        assertThatThrownBy(() -> ask(client, first(client, MODEL, "hello"), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(dispatched).containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
        assertThat(logs).singleElement().satisfies(row -> {
            assertThat(row.success()).isFalse();
            assertThat(row.stopReason()).isEqualTo("INTERRUPTED");
            // Nothing the relay counted is written as the user's usage or credits.
            assertThat(row.inputTokens()).isZero();
            assertThat(row.outputTokens()).isZero();
            assertThat(row.credits()).isEqualByComparingTo("0");
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aClientThatGivesUpWhileTheRelayIsGeneratingReleasesTheReservationForGoodAndAsksNothingElse(Client client)
        throws InterruptedException {
        serve(new Setup().relays("relay-1", "relay-2"));
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        relays.next("relay-1", Reply.holding(arrived, letGo, Reply.text("too late")));

        Disposable request = start(client, first(client, MODEL, "hello"), "session-1").subscribe(response -> { }, error -> { });
        assertThat(arrived.await(30, TimeUnit.SECONDS)).as("the relay has the request").isTrue();
        request.dispose();
        letGo.countDown();

        // The relay may be generating the answer, and nobody will be charged for it: the operator carries that, and the
        // reservation is not left to a review (design 7.8, invariant I7). Nothing else is asked for the request.
        await().atMost(Duration.ofSeconds(20)).until(() -> !ledger.released.isEmpty());
        assertThat(ledger.released).singleElement().satisfies(release -> {
            assertThat(release.definitive()).isTrue();
            assertThat(release.reason()).isEqualTo("Client disconnected; cross-protocol attempt released");
        });
        assertThat(ledger.settled).isEmpty();
        assertThat(dispatched).containsExactly("relay-1");

        // The client has gone, and the session is where it was: its next request is the relay's, at the relay's price.
        Turn next = ask(client, first(client, MODEL, "hello again"), "session-1");
        assertServedBy(next.response(), "relay", "1");
        ledger.assertCharged("anthropic:relay", 1, "1");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void theClientsRetryOfAnInterruptedAnswerIsServedByKiroAndTheRelayServesAgainAfterTwoMinutes(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.cutAfterStart("I will run the"));
        ObjectNode request = first(client, MODEL, "hello");
        assertThatThrownBy(() -> ask(client, request, "session-1")).isInstanceOf(ApiException.class);

        // The SDK retries a 502: not with the relay that may still be generating the first answer.
        Turn retry = ask(client, request, "session-1");

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertServedBy(retry.response(), "kiro", "2");
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-1");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.INTERRUPTED);
        });
        assertThat(ledger.released).hasSize(1);
        ledger.assertCharge(ledger.settled.getFirst(), "anthropic:kiro", 2, "0.6");

        // Another conversation of the same key has nothing against the relay.
        Turn other = ask(client, first(client, MODEL, "hello"), "session-2");
        assertServedBy(other.response(), "relay", "1");

        // Kiro answering in the relay's place was the request's business, not a fall of the session: after the two minutes
        // the relay serves it again, at its own price.
        advance(Duration.ofMinutes(2).plusSeconds(1));
        Turn later = ask(client, first(client, MODEL, "hello"), "session-1");
        assertServedBy(later.response(), "relay", "1");
        assertThat(ledger.settled).extracting(Settled::tierRef)
            .containsExactly("anthropic:kiro", "anthropic:relay", "anthropic:relay");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatReportsFarMoreInputThanTheRequestCouldHaveHadIsChargedNoMoreThanWasReserved(Client client) {
        assertChargedNoMoreThanReservedForAnswerClaiming(client, 90_000_000L, TieredRelayScenarioRelays.OUTPUT_TOKENS);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatReportsFarMoreOutputThanTheRequestAllowedIsChargedNoMoreThanWasReserved(Client client) {
        assertChargedNoMoreThanReservedForAnswerClaiming(client, TieredRelayScenarioRelays.INPUT_TOKENS, 9_000_000L);
    }

    /** The relay's word about its usage is trusted only as far as the request could have gone (design 5.8, invariant I2). */
    private void assertChargedNoMoreThanReservedForAnswerClaiming(Client client, long claimedIn, long claimedOut) {
        serve(new Setup());
        relays.always("relay-1", Reply.textReporting("from relay-1", claimedIn, claimedOut));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        assertServedBy(turn.response(), "relay", "1");
        Settled charge = ledger.settled.getFirst();
        assertThat(charge.tierRef()).isEqualTo("anthropic:relay");
        BigDecimal claimed = QUOTE.cost(new PlatformUsage(claimedIn, claimedOut, BigDecimal.ZERO, 0, 0, 0));
        assertThat(charge.credits()).isLessThanOrEqualTo(ledger.reservedCredits()).isLessThan(claimed);
        assertThat(logs).singleElement().satisfies(row -> assertThat(row.usageSource()).isEqualTo("CLAMPED"));
        assertThat(ledger.released).isEmpty();
    }

    // ---- requests the relay cannot carry go below without moving the session (design 3, 7.9, 4.7) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRequestWithAServerSideSearchLeavesTheRelayOutAndTheMainLoopStaysInTheFirstTier(Client client) {
        serve(new Setup());

        Turn main = ask(client, first(client, MODEL, "Run echo hello with the Bash tool"), "session-1");
        Turn search = ask(client, webSearch(client, MODEL), "session-1");
        Turn mainAgain = ask(client, first(client, MODEL, "Now run echo world"), "session-1");

        assertServedBy(main.response(), "relay", "1");
        assertServedBy(search.response(), "kiro", "2");
        assertServedBy(mainAgain.response(), "relay", "1");
        // The relay was not asked the search: the plan had left it out for a feature it cannot carry.
        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "relay-1");
        assertThat(plans.get(1).xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-1");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.FEATURE);
        });
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:relay", "anthropic:kiro", "anthropic:relay"));
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aSearchAndAModelTheRelayHasNoMappingForInterleavedWithTheMainLoopLeaveItInTheFirstTier(Client client) {
        serve(new Setup());
        List<String> served = new ArrayList<>();

        served.add(servedTier(ask(client, first(client, MODEL, "Run echo hello"), "session-1")));
        served.add(servedTier(ask(client, webSearch(client, MODEL), "session-1")));
        // The small fast model Claude Code writes the title of a conversation with: the relay maps Sonnet and Opus only.
        served.add(servedTier(ask(client, first(client, HAIKU, "Write a title"), "session-1")));
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> assertThat(skip.reason()).isEqualTo(XpSkip.Reason.NO_MAPPING));
        served.add(servedTier(ask(client, first(client, MODEL, "Run echo world"), "session-1")));
        served.add(servedTier(ask(client, webSearch(client, MODEL), "session-1")));
        served.add(servedTier(ask(client, first(client, HAIKU, "Write a title"), "session-1")));
        served.add(servedTier(ask(client, first(client, MODEL, "Run echo again"), "session-1")));

        // Neither kind of request is a fall of the session: the main loop is served by the first tier every time.
        assertThat(served).containsExactly("relay", "kiro", "kiro", "relay", "kiro", "kiro", "relay");
        assertThat(ledger.settled).extracting(Settled::tierRef).containsExactly("anthropic:relay", "anthropic:kiro", "anthropic:kiro",
            "anthropic:relay", "anthropic:kiro", "anthropic:kiro", "anthropic:relay");
        assertThat(ledger.settled).allSatisfy(charge -> assertThat(charge.credits())
            .isEqualByComparingTo(QUOTE.scaled(charge.factor()).cost(USAGE)));
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aSessionThatReallyFellStaysBelowWhateverElseItSendsUntilItHasBeenIdleForTenMinutes(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.failure(404, "model_not_found"));

        Turn fell = ask(client, first(client, MODEL, "Run echo hello"), "session-1");

        assertServedBy(fell.response(), "kiro", "2");
        ledger.assertCharge(ledger.settled.getLast(), "anthropic:kiro", 2, "0.6");

        // The relay is well again, and the session does not go back and forth: it was moved down, and it stays.
        dispatched.clear();
        Turn stays = ask(client, first(client, MODEL, "Run echo world"), "session-1");
        assertServedBy(stays.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(ledger.holdLevels()).as("what the second request reserves for").containsExactly("anthropic:kiro");
        // The requests that look away from the relay anyway change nothing about it.
        assertServedBy(ask(client, webSearch(client, MODEL), "session-1").response(), "kiro", "2");
        assertServedBy(ask(client, first(client, HAIKU, "Write a title"), "session-1").response(), "kiro", "2");
        assertServedBy(ask(client, first(client, MODEL, "Run echo again"), "session-1").response(), "kiro", "2");
        assertThat(relays.seen()).as("the relay was asked once, for the first request").hasSize(1);

        // Another conversation is a new session and starts from the best tier.
        assertServedBy(ask(client, first(client, MODEL, "hello"), "session-2").response(), "relay", "1");

        // Every use of the session renews it; nine idle minutes later it is still there, and ten make it a new one.
        advance(Duration.ofMinutes(9));
        assertServedBy(ask(client, first(client, MODEL, "still here"), "session-1").response(), "kiro", "2");
        advance(Duration.ofMinutes(9));
        assertServedBy(ask(client, first(client, MODEL, "and here"), "session-1").response(), "kiro", "2");
        advance(Duration.ofMinutes(10).plusSeconds(1));
        Turn back = ask(client, first(client, MODEL, "back again"), "session-1");
        assertServedBy(back.response(), "relay", "1");
        ledger.assertCharge(ledger.settled.getLast(), "anthropic:relay", 1, "1");
        assertThat(ledger.released).isEmpty();
    }

    // ---- the conversation as the clients keep it ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayThatGivesEveryToolCallTheSameIdIsHeardUnderIdsOfTheGatewayAndANativeAccountIsSentThoseToo(Client client) {
        serve(new Setup().natives(anthropicKey("anthropic-1"), kiro("kiro-1")));
        relays.always("relay-1", Reply.toolCall("call_0", client.toolName, "{\"command\":\"echo hello\"}"));
        ObjectNode firstRequest = first(client, MODEL, "Run echo hello with the Bash tool");

        Turn firstTurn = ask(client, firstRequest, "session-1");

        // The client is given an id of the gateway's, whatever the relay called the call.
        assertThat(firstTurn.answer().calls()).singleElement().satisfies(call -> {
            assertThat(call.id()).matches(client.messages() ? MINTED_MESSAGES_ID : MINTED_CALL_ID);
            assertThat(call.name()).isEqualTo(client.toolName);
            assertThat(json.readTree(call.arguments()).path("command").asString()).isEqualTo("echo hello");
        });
        String firstId = firstTurn.answer().calls().getFirst().id();

        relays.always("relay-1", Reply.toolCall("call_0", client.toolName, "{\"command\":\"echo world\"}"));
        ObjectNode secondRequest = followUp(client, firstRequest, firstTurn.answer(), "hello");
        Turn secondTurn = ask(client, secondRequest, "session-1");
        String secondId = secondTurn.answer().calls().getFirst().id();
        assertThat(secondId).matches(client.messages() ? MINTED_MESSAGES_ID : MINTED_CALL_ID).isNotEqualTo(firstId);
        // The relay was shown the history under the ids the client has, not under its own call_0 of the first turn.
        List<String> relayHistoryIds = new ArrayList<>();
        collectToolIds(relays.seen().get(1).body(), relayHistoryIds);
        assertThat(relayHistoryIds).containsExactly(firstId, firstId);

        // The third turn finds the relay unable to serve the model, and a native account takes the conversation over.
        relays.always("relay-1", Reply.failure(404, "model_not_found"));
        ObjectNode thirdRequest = followUp(client, secondRequest, secondTurn.answer(), "world");
        Turn thirdTurn = ask(client, thirdRequest, "session-1");

        assertServedBy(thirdTurn.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("relay-1", "relay-1", "relay-1", client.nativeFirst());
        // What it is sent of the history: two calls and their results, under the distinct ids the client was given and
        // none of the relay's.
        assertThat(nativeCalls).singleElement().satisfies(call -> {
            assertThat(call.account()).isEqualTo(client.nativeFirst());
            assertThat(call.toolIds()).isNotEmpty().doesNotContain("call_0");
            assertThat(call.toolIds()).allMatch(id -> id.equals(firstId) || id.equals(secondId));
            assertThat(call.toolIds()).contains(firstId, secondId);
        });
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:relay", "anthropic:relay", "anthropic:kiro"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void anEmptyToolOutputInTheHistoryIsStillServedByTheRelay(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.toolCall("call_7Hk2Qa", client.toolName, "{\"command\":\"true\"}"));
        ObjectNode firstRequest = first(client, MODEL, "Run true with the Bash tool");
        Turn firstTurn = ask(client, firstRequest, "session-1");

        // The command printed nothing: the client's result is an empty string.
        ObjectNode secondRequest = followUp(client, firstRequest, firstTurn.answer(), "");
        Turn secondTurn = ask(client, secondRequest, "session-1");

        assertServedBy(secondTurn.response(), "relay", "1");
        assertThat(plans.getLast().xpSkips()).isEmpty();
        // The relay is given a result to answer, not an empty one, which strict relays refuse.
        JsonNode sent = relays.seen().get(1).body();
        assertThat(sent.toString()).contains("[no output]");
        // And so on for as long as the conversation goes: a third turn after an answer.
        ObjectNode thirdRequest = secondRequest.deepCopy();
        thirdRequest.withArray("messages").addObject().put("role", "assistant").put("content", secondTurn.answer().text());
        thirdRequest.withArray("messages").addObject().put("role", "user").put("content", "and once more please");
        Turn thirdTurn = ask(client, thirdRequest, "session-1");
        assertServedBy(thirdTurn.response(), "relay", "1");
        assertThat(dispatched).containsExactly("relay-1", "relay-1", "relay-1");
        assertThat(ledger.released).isEmpty();
        assertThat(ledger.settled).hasSize(3).allSatisfy(charge -> assertThat(charge.tierRef()).isEqualTo("anthropic:relay"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HistoryWithNothingToSay.class)
    void theOtherHistoriesClaudeCodeWritesWithNothingInThemAreStillServedByTheRelayForAsLongAsTheConversationGoes(
        HistoryWithNothingToSay emptiness) {
        serve(new Setup());
        relays.next("relay-1", Reply.toolCall("call_7Hk2Qa", "Bash", "{\"command\":\"true\"}"));
        ObjectNode firstRequest = first(Client.CLAUDE_CODE, MODEL, "Run true with the Bash tool");
        Turn firstTurn = ask(Client.CLAUDE_CODE, firstRequest, "session-1");

        ObjectNode request = emptiness.follow(json, firstRequest, firstTurn.answer().calls().getFirst().id());
        for (int turn = 0; turn < 3; turn++) {
            Turn served = ask(Client.CLAUDE_CODE, request, "session-1");
            assertServedBy(served.response(), "relay", "1");
            assertThat(plans.getLast().xpSkips()).isEmpty();
            // What the relay is given says something in every message it reads, which strict relays insist on.
            relays.seen().getLast().body().path("messages").forEach(message -> {
                if (message.path("role").asString().equals("tool") || message.path("role").asString().equals("user")) {
                    JsonNode content = message.path("content");
                    assertThat(content.isArray() ? content.size() : content.asString("").length())
                        .as("the %s message %s", message.path("role").asString(), message).isPositive();
                }
            });
            request = request.deepCopy();
            request.withArray("messages").addObject().put("role", "assistant").put("content", served.answer().text());
            request.withArray("messages").addObject().put("role", "user").put("content", "and once more please");
        }

        assertThat(dispatched).containsOnly("relay-1").hasSize(4);
        assertThat(ledger.settled).hasSize(4).allSatisfy(charge -> assertThat(charge.tierRef()).isEqualTo("anthropic:relay"));
        assertThat(ledger.released).isEmpty();
    }

    /** The turns Claude Code sends after a command whose result is nothing, or nothing a relay is given. */
    private enum HistoryWithNothingToSay {
        TOOL_RESULT_WITHOUT_CONTENT {
            @Override
            ObjectNode follow(ObjectMapper json, ObjectNode previous, String callId) {
                ObjectNode body = withCall(json, previous, callId);
                lastResult(body).remove("content");
                return body;
            }
        },
        TOOL_RESULT_OF_AN_IMAGE_ONLY {
            @Override
            ObjectNode follow(ObjectMapper json, ObjectNode previous, String callId) {
                ObjectNode body = withCall(json, previous, callId);
                lastResult(body).set("content", image(json));
                return body;
            }
        },
        USER_TURN_OF_AN_IMAGE_ONLY {
            @Override
            ObjectNode follow(ObjectMapper json, ObjectNode previous, String callId) {
                ObjectNode body = withCall(json, previous, callId);
                body.withArray("messages").addObject().put("role", "assistant").put("content", "Here is the output.");
                body.withArray("messages").addObject().put("role", "user").set("content", image(json));
                return body;
            }
        };

        abstract ObjectNode follow(ObjectMapper json, ObjectNode previous, String callId);

        /** The history after the call of {@code callId} with its result: an empty string, to be taken out by the variant. */
        ObjectNode withCall(ObjectMapper json, ObjectNode previous, String callId) {
            ObjectNode body = previous.deepCopy();
            ArrayNode messages = body.withArray("messages");
            ObjectNode call = json.createObjectNode().put("type", "tool_use").put("id", callId).put("name", "Bash");
            call.putObject("input").put("command", "true");
            messages.addObject().put("role", "assistant").putArray("content").add(call);
            messages.addObject().put("role", "user").putArray("content").addObject()
                .put("type", "tool_result").put("tool_use_id", callId).put("content", "");
            return body;
        }

        /** The result block of the call that {@link #withCall} added. */
        ObjectNode lastResult(ObjectNode body) {
            ArrayNode messages = body.withArray("messages");
            return (ObjectNode) messages.get(messages.size() - 1).path("content").get(0);
        }

        /** One 1x1 picture, as the content blocks of a message carry it. */
        ArrayNode image(ObjectMapper json) {
            ArrayNode blocks = json.createArrayNode();
            ObjectNode source = blocks.addObject().put("type", "image").putObject("source");
            source.put("type", "base64").put("media_type", "image/png").put("data",
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
            return blocks;
        }
    }

    // ---- the window of the relay ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRequestThatCompactsTheConversationBeyondTheWindowOfTheRelayGoesToKiroAndMovesNoSession(Client client) {
        serve(new Setup().window(20_000));

        Turn short1 = ask(client, first(client, MODEL, "Run echo hello"), "session-1");
        // The whole history and an instruction to summarize it: about 30,000 tokens, past the 20,000 the relay is set to.
        Turn compaction = ask(client, history(client, MODEL, 20, 6_000), "session-1");
        Turn short2 = ask(client, first(client, MODEL, "Run echo world"), "session-1");

        assertServedBy(short1.response(), "relay", "1");
        assertServedBy(compaction.response(), "kiro", "2");
        assertServedBy(short2.response(), "relay", "1");
        // The relay was never sent the long request, and the plan says why it was left out.
        assertThat(relays.seen()).hasSize(2);
        assertThat(plans.get(1).xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-1");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.CONTEXT);
        });
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:relay", "anthropic:kiro", "anthropic:relay"));
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPromptTheRelayRefusedForItsLengthIsLearnedAndOnlyTheLongOnesGoToKiroUntilItIsForgotten(Client client) {
        serve(new Setup());
        // The relay's window is smaller than the one it was registered with; it says so to the first long request.
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));

        Turn refused = ask(client, history(client, MODEL, 10, 6_000), "session-1");

        assertServedBy(refused.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        // The crossing was for the request's sake and not a fall of the session.
        Turn shortOne = ask(client, first(client, MODEL, "Run echo hello"), "session-1");
        assertServedBy(shortOne.response(), "relay", "1");

        // The same long request is not sent to the relay again, a short one still is.
        dispatched.clear();
        Turn longAgain = ask(client, history(client, MODEL, 10, 6_000), "session-1");
        assertServedBy(longAgain.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.CONTEXT);
            assertThat(skip.detail()).contains("refused for its length");
        });
        assertServedBy(ask(client, first(client, MODEL, "Run echo world"), "session-1").response(), "relay", "1");

        // What a relay refused is forgotten after six hours: it may have a larger window by then.
        advance(Duration.ofHours(6).plusSeconds(1));
        assertServedBy(ask(client, history(client, MODEL, 10, 6_000), "session-1").response(), "relay", "1");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:relay", "anthropic:kiro",
            "anthropic:relay", "anthropic:relay"));
        assertThat(ledger.released).isEmpty();
    }

    // ---- a relay that sat the request out for the request's sake keeps no session away from the tier (design 0, 4.7) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aCompactionRequestThatOnlyTheBusyRelayCouldTakeIsServedByKiroAndMovesNoSession(Client client) {
        // Two relays in the first tier, the first with a window of 20,000 tokens and the second with one of 200,000.
        serve(new Setup().relays("relay-1", "relay-2").windowOf("relay-1", 20_000).windowOf("relay-2", 200_000));
        busy.add("relay-2");

        Turn compaction = ask(client, history(client, MODEL, 20, 6_000), "session-1");

        // The first relay is too small for the whole history, the second is busy: Kiro serves it.
        assertServedBy(compaction.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-1");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.CONTEXT);
        });

        // The next request of the session is an ordinary one, and the relay that was left out for the history would serve it.
        busy.clear();
        dispatched.clear();
        Turn next = ask(client, first(client, MODEL, "Run echo hello"), "session-1");

        assertServedBy(next.response(), "relay", "1");
        assertThat(dispatched).singleElement().asString().startsWith("relay-");
        assertThat(ledger.holdLevels()).as("what the second request reserves for").containsExactly("anthropic:relay", "anthropic:kiro");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:relay"));
        assertThat(ledger.released).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRetryServedByKiroWhileTheOtherRelayIsBusyDoesNotKeepTheSessionThereAfterTheInterruptionIsOver(Client client) {
        serve(new Setup().relays("relay-1", "relay-2"));
        relays.next("relay-1", Reply.cutAfterStart("I will run the"));
        busy.add("relay-2");
        ObjectNode request = first(client, MODEL, "hello");
        assertThatThrownBy(() -> ask(client, request.deepCopy(), "session-1")).isInstanceOf(ApiException.class);

        // The SDK retries: the first relay is kept from the session for two minutes and the other is busy.
        Turn retry = ask(client, request.deepCopy(), "session-1");

        assertServedBy(retry.response(), "kiro", "2");
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-1");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.INTERRUPTED);
        });

        // Both relays are free two minutes later, and the session is where it was: the relay serves it, not Kiro.
        busy.clear();
        advance(Duration.ofMinutes(2).plusSeconds(1));
        Turn later = ask(client, first(client, MODEL, "and now run echo world"), "session-1");

        assertServedBy(later.response(), "relay", "1");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:relay"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aSessionThatBothRelaysTurnedAwayEvenSoFellAndStaysWithKiro(Client client) {
        serve(new Setup().relays("relay-1", "relay-2"));
        busy.addAll(Set.of("relay-1", "relay-2"));

        Turn fell = ask(client, first(client, MODEL, "hello"), "session-1");

        assertServedBy(fell.response(), "kiro", "2");
        busy.clear();
        dispatched.clear();
        Turn stays = ask(client, first(client, MODEL, "and again"), "session-1");
        assertServedBy(stays.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    // ---- a prompt that no source of the tiers takes (design 3, 7.8, 7.9) ----

    private ObjectNode pinned(ObjectNode body, String suffix) {
        body.put("model", body.path("model").asString() + "@" + suffix);
        return body;
    }

    private ApiException refusedWith(Client client, ObjectNode body, String session) {
        try {
            ask(client, body, session);
        } catch (ApiException refused) {
            return refused;
        }
        throw new AssertionError("The request was not refused");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPinnedPromptTheRelayRefusedForItsLengthIsToldThatItIsTooLongTheFirstTimeAndEveryTimeAfter(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));

        // The relay says it first, and this node learns the size; the plan says it the next time, without asking the relay.
        ApiException first = refusedWith(client, pinned(history(client, MODEL, 10, 6_000), "relay"), "session-1");
        ApiException again = refusedWith(client, pinned(history(client, MODEL, 10, 6_000), "relay"), "session-1");

        assertThat(relays.seen()).as("the relay was asked once").hasSize(1);
        assertThat(first.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(first.getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(first.getMessage()).startsWith("prompt is too long");
        assertThat(again.getStatus()).isEqualTo(first.getStatus());
        assertThat(again.getCode()).isEqualTo(first.getCode());
        assertThat(again.getMessage()).isEqualTo(first.getMessage());
        // A short prompt is the relay's all the same, and nothing was left for review.
        assertServedBy(ask(client, pinned(first(client, MODEL, "hello"), "relay"), "session-1").response(), "relay", "1", "pinned");
        assertThat(ledger.settled).hasSize(1);
        assertThat(ledger.released).hasSize(1).allSatisfy(release -> assertThat(release.definitive()).isTrue());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPromptTheRelayRefusedForItsLengthIsToldToTryAgainAndNotToShortenItWhileKiroIsOnlyBusy(Client client) {
        serve(new Setup());
        busy.add("kiro-1");
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));

        // Claude Code shortens a conversation on "prompt is too long", at a loss, for a prompt Kiro takes as it is.
        ApiException first = refusedWith(client, history(client, MODEL, 10, 6_000), "session-1");
        ApiException again = refusedWith(client, history(client, MODEL, 10, 6_000), "session-1");

        assertThat(first.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(again.getStatus()).isEqualTo(first.getStatus());
        assertThat(again.getCode()).isEqualTo(first.getCode());
        assertThat(relays.seen()).as("the relay was asked once").hasSize(1);
        // The relay had the request when it was refused: the reservation is released for good, not left for review.
        assertThat(ledger.released).hasSize(2);
        assertThat(ledger.released.getFirst().definitive()).isTrue();
        assertThat(ledger.settled).isEmpty();

        busy.clear();
        Turn served = ask(client, history(client, MODEL, 10, 6_000), "session-1");
        assertServedBy(served.response(), "kiro", "2");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPromptTheRelayRefusedForItsLengthIsToldToTryAgainWhenAHeaderNamedItsTierAndTheRetryIsServedByKiro(Client client) {
        serve(new Setup());
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));

        // The header is a hint: it gives way once this node knows the relay does not take the prompt, so the client is
        // told to try again and not to shorten the conversation Kiro takes as it is.
        ApiException first = refusedWith(client, history(client, MODEL, 10, 6_000), "session-1");

        assertThat(first.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
        assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(ledger.released).hasSize(1);
        assertThat(ledger.released.getFirst().definitive()).isTrue();

        Turn retry = ask(client, history(client, MODEL, 10, 6_000), "session-1");

        assertThat(servedTier(retry)).isEqualTo("kiro");
        assertThat(retry.response().getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(retry.response().getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
        assertThat(relays.seen()).as("the relay was asked once").hasSize(1);
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPromptARelayRefusedAfterTheBudgetWasSpentIsToldToTryAgainWhileKiroIsFree(Client client) {
        serve(new Setup().relays("relay-1", "relay-2").reservedSlot(false));
        relays.next("relay-1", Reply.failure(503, "server_error"));
        relays.next("relay-2", Reply.failure(400, "context_length_exceeded"));

        // The two dispatches of the budget went to the relays and there is no slot to cross on, but Kiro is free and takes
        // the prompt as it is: the client is not told to shorten it.
        ApiException first = refusedWith(client, history(client, MODEL, 10, 6_000), "session-1");

        assertThat(first.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());

        // What the identical request is told next: this node has learned what the second relay refused.
        Turn retry = ask(client, history(client, MODEL, 10, 6_000), "session-1");
        assertThat(servedTier(retry)).isEqualTo("relay");
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-2");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.CONTEXT);
        });
    }

    /** The ways a client names the relay tier and the tiers below it, and no tier at all (design 6.3). */
    private enum Naming {
        AUTOMATIC, SUFFIX, HEADER, PREFERENCE;

        boolean pinned() {
            return this != AUTOMATIC;
        }
    }

    static Stream<Arguments> clientsNamingsAndRefusals() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(Naming.values()).flatMap(naming ->
            Stream.of("context_length_exceeded", "invalid_request_error").flatMap(refusal ->
                Stream.of(true, false).map(slot -> Arguments.of(client, naming, refusal, slot)))));
    }

    @ParameterizedTest(name = "{0}, {1}, {2}, reserved slot {3}")
    @MethodSource("clientsNamingsAndRefusals")
    void aRelayRefusalAfterAnotherRelayFailedIsToldToTryAgainWhileKiroIsBusyWhateverTheReservedSlot(
        Client client, Naming naming, String refusal, boolean reservedSlot
    ) {
        serve(new Setup().relays("relay-1", "relay-2").reservedSlot(reservedSlot));
        busy.add("kiro-1");
        relays.next("relay-1", Reply.failure(503, "server_error"));
        relays.next("relay-2", Reply.failure(400, refusal));
        ObjectNode body = history(client, MODEL, 10, 6_000);
        switch (naming) {
            case SUFFIX -> pinned(body, "relay+");
            case HEADER -> extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay+");
            case PREFERENCE -> when(apiKeyService.authenticate(any())).thenReturn(Mono.just(new ApiKeyPrincipal(KEY, null, null,
                "test", null, BigDecimal.ZERO, BigDecimal.ZERO, "anthropic", false, "BALANCE", "relay+")));
            default -> { }
        }

        // The second relay's refusal crosses to Kiro on the slot that is kept for it, or cannot cross without one, and Kiro
        // is busy for now: the answer is the same either way, and the first relay's 503 does not take its place.
        ApiException refused = refusedWith(client, body, "session-1");

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        if (naming.pinned()) {
            assertUnavailableToTryAgain(refused);
            assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        } else {
            assertThat(refused.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNull();
        }
        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPromptARelayRefusedWhenTheLeaseCannotCoverAnotherDispatchIsToldToTryAgainWhileKiroIsFree(Client client) {
        serve(new Setup());
        // The reservation can be leased for ten minutes more, which is less than a second dispatch is given.
        ApiKeyService.DispatchLease nearTheCap = new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(10));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenReturn(Mono.just(nearTheCap));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(nearTheCap));
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));

        ApiException first = refusedWith(client, history(client, MODEL, 10, 6_000), "session-1");

        assertThat(first.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());

        // The identical request is served: the relay is left out of it by what it refused, and Kiro takes it as it is.
        Turn retry = ask(client, history(client, MODEL, 10, 6_000), "session-1");
        assertServedBy(retry.response(), "kiro", "2");
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
    }

    /** How a hint names the tiers a request is pinned to, and whether those hold Kiro (design 6.3, 7.8). */
    private enum Hint {
        HEADER_TIER_AND_BELOW(true, "relay+", true),
        HEADER_LEVEL_AND_BELOW(true, "1+", true),
        PREFERENCE_TIER_AND_BELOW(false, "relay+", true),
        HEADER_TIER(true, "relay", false),
        PREFERENCE_TIER(false, "relay", false);

        private final boolean header;
        private final String reference;
        private final boolean holdsKiro;

        Hint(boolean header, String reference, boolean holdsKiro) {
            this.header = header;
            this.reference = reference;
            this.holdsKiro = holdsKiro;
        }

        @Override
        public String toString() {
            return (header ? "header " : "preference ") + reference;
        }
    }

    static Stream<Arguments> clientsAndHints() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(Hint.values()).map(hint -> Arguments.of(client, hint)));
    }

    private void nameTheTiersBy(Hint hint) {
        if (hint.header) {
            extraHeaders.put(TierSelectorParser.REQUEST_HEADER, hint.reference);
        } else {
            when(apiKeyService.authenticate(any())).thenReturn(Mono.just(new ApiKeyPrincipal(KEY, null, null, "test", null,
                BigDecimal.ZERO, BigDecimal.ZERO, "anthropic", false, "BALANCE", hint.reference)));
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("clientsAndHints")
    void aConversionTheRelayRefusedIsToldToTryAgainWhenTheTiersAHintNamesHoldKiroAndKiroIsOnlyBusy(Client client, Hint hint) {
        serve(new Setup());
        nameTheTiersBy(hint);
        busy.add("kiro-1");
        relays.next("relay-1", Reply.failure(400, "invalid_request_error"));

        ApiException refused = refusedWith(client, first(client, MODEL, "hello"), "session-1");

        assertThat(dispatched).as("Kiro is busy and was not asked").containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());
        busy.clear();
        relays.next("relay-1", Reply.failure(400, "invalid_request_error"));
        if (hint.holdsKiro) {
            // The range has Kiro below the relay: the next request crosses to it, so the client is not told not to retry.
            assertUnavailableToTryAgain(refused);
            assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
            Turn retry = ask(client, first(client, MODEL, "hello"), "session-1");
            assertServedBy(retry.response(), "kiro", "2", "pinned");
            assertThat(dispatched).containsExactly("relay-1", "relay-1", "kiro-1");
            ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro"));
        } else {
            // A tier of relays alone has nothing else to try, and the retry is refused the same way.
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
            assertThat(refused.getMessage()).startsWith("Source error 400");
            ApiException again = refusedWith(client, first(client, MODEL, "hello"), "session-1");
            assertThat(again.getMessage()).isEqualTo(refused.getMessage());
            assertThat(dispatched).containsExactly("relay-1", "relay-1");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPinnedPromptBeyondTheWindowTheAdministratorWroteIsToldThatItIsTooLongToo(Client client) {
        serve(new Setup().window(20_000));

        ApiException refused = refusedWith(client, pinned(history(client, MODEL, 20, 6_000), "relay"), "session-1");

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(refused.getMessage()).startsWith("prompt is too long");
        assertThat(relays.seen()).isEmpty();
        assertThat(dispatched).isEmpty();
        assertThat(ledger.reserved).isEmpty();
        assertServedBy(ask(client, pinned(first(client, MODEL, "hello"), "relay"), "session-1").response(), "relay", "1", "pinned");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aPromptBeyondTheWindowIsToldThatItIsTooLongWhenNoNativeAccountIsThereToServeIt(Client client) {
        serve(new Setup().window(20_000).natives());

        ApiException refused = refusedWith(client, history(client, MODEL, 20, 6_000), "session-1");

        // Not "no available account", which clients retry and cannot make anything of.
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getMessage()).startsWith("prompt is too long");
        assertThat(dispatched).isEmpty();
        assertThat(ledger.reserved).isEmpty();
    }

    // ---- a relay that broke off an answer, for a client that pinned its tier (design 6.3, 7.8) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void theRetryOfAPinnedRequestWhoseOnlyRelayBrokeOffIsToldToTryAgainAndIsServedWhenTheTwoMinutesAreOver(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.cutAfterStart("I will run the"));
        ObjectNode request = pinned(first(client, MODEL, "hello"), "relay");
        assertThatThrownBy(() -> ask(client, request.deepCopy(), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // The SDK retries a 502 by itself: it is not told that the tier has no account for the model, and not served by Kiro.
        ApiException retry = refusedWith(client, request.deepCopy(), "session-1");

        assertThat(retry.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(retry.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
        assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();

        advance(Duration.ofMinutes(2).plusSeconds(1));
        Turn later = ask(client, request.deepCopy(), "session-1");
        assertThat(later.answer().text()).isEqualTo("from relay-1");
        assertThat(later.response().getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("pinned");
        ledger.assertCharge(ledger.settled.getFirst(), "anthropic:relay", 1, "1");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void theRetryOfARequestWhoseHeaderNamesTheRelayTierAndWhoseOnlyRelayBrokeOffIsToldToTryAgainNotServedByKiro(Client client) {
        serve(new Setup());
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");
        relays.next("relay-1", Reply.cutAfterStart("I will run the"));
        assertThatThrownBy(() -> ask(client, first(client, MODEL, "hello"), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // The header is a hint, and a hint to a tier that can serve again shortly is kept as the suffix is: the client is
        // not given Kiro at Kiro's price for two minutes, it is told to try again.
        ApiException retry = refusedWith(client, first(client, MODEL, "hello"), "session-1");

        assertThat(retry.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(retry.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
        assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(ledger.settled).isEmpty();
        // The answer that was cut is released for good; the retry never reserved anything that stays.
        assertThat(ledger.released).hasSize(2);
        assertThat(ledger.released.getFirst().definitive()).isTrue();

        advance(Duration.ofMinutes(2).plusSeconds(1));
        Turn later = ask(client, first(client, MODEL, "hello"), "session-1");
        assertThat(later.answer().text()).isEqualTo("from relay-1");
        assertThat(later.response().getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("pinned");
        ledger.assertCharge(ledger.settled.getFirst(), "anthropic:relay", 1, "1");
    }

    // ---- a relay that is switched off, for a client that is set to send the tier with every request (design 6.3) ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aHeaderForARelayTierWhoseRelayIsSwitchedOffHoldsOnlyTheRequestsTheRelayWouldHaveServed(Client client) {
        serve(new Setup().relaysSwitchedOff(true));
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");

        // The model the relay serves: the pool is out of service for now, and the answer says so, not Kiro's price.
        ApiException unavailable = refusedWith(client, first(client, MODEL, "hello"), "session-1");
        assertThat(unavailable.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
        assertThat(dispatched).isEmpty();

        // The small model of Claude Code that the relay has no mapping for, and the web search it asks for: the header does
        // not fit them, as it does not when the relay is in service, and they are served in the order of the tiers.
        for (ObjectNode request : List.of(first(client, HAIKU, "Write a title"), webSearch(client, MODEL))) {
            Turn turn = ask(client, request, "session-1");
            assertThat(servedTier(turn)).isEqualTo("kiro");
            assertThat(turn.response().getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
            assertThat(turn.response().getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        }
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:kiro"));
        assertThat(ledger.released).hasSize(1);
    }

    // ---- a pin is kept for a relay that is out for a moment, and for no request the relay would not have taken (design 6.3) ----

    private void assertServedByKiroDespiteTheHeader(Turn turn) {
        assertThat(servedTier(turn)).isEqualTo("kiro");
        assertThat(turn.response().getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(turn.response().getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
    }

    private static void assertUnavailableToTryAgain(ApiException refused) {
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
    }

    private static void assertTooLong(ApiException refused) {
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(refused.getMessage()).startsWith("prompt is too long");
    }

    /** Another request of the session, for the Opus model, is cut mid-stream on the relay: the relay is kept from the session for two minutes. */
    private void cutAnotherRequestOfTheSession(Client client, boolean pinnedToTheRelay) {
        relays.next("relay-1", Reply.cutAfterStart("I will run the"));
        ObjectNode cut = first(client, "claude-opus-4.5", "Run echo hello");
        assertThat(refusedWith(client, pinnedToTheRelay ? pinned(cut, "relay") : cut, "session-1").getStatus())
            .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aHeaderForTheRelayTierIsNotHeldByAnInterruptionForARequestTheRelayWouldNotHaveTakenAnyway(Client client) {
        serve(new Setup().window(20_000));
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");
        ObjectNode compaction = history(client, MODEL, 20, 6_000);

        // The whole history is beyond the window of the relay: the header does not fit it, and Kiro serves it.
        assertServedByKiroDespiteTheHeader(ask(client, compaction.deepCopy(), "session-1"));
        cutAnotherRequestOfTheSession(client, false);

        // The relay is kept from the session for two minutes, which changes nothing for a request it would not have taken:
        // it is Kiro's all the same, and not held for a relay that has nothing to do with it.
        Turn during = ask(client, compaction.deepCopy(), "session-1");
        assertServedByKiroDespiteTheHeader(during);
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.accountId()).isEqualTo("relay-1");
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.CONTEXT);
        });
        // An ordinary request of the session is the relay's, and is held for it.
        assertUnavailableToTryAgain(refusedWith(client, first(client, MODEL, "hello"), "session-1"));
        assertThat(lastExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();

        advance(Duration.ofMinutes(2).plusSeconds(1));
        assertServedByKiroDespiteTheHeader(ask(client, compaction.deepCopy(), "session-1"));
        Turn later = ask(client, first(client, MODEL, "hello"), "session-1");
        assertServedBy(later.response(), "relay", "1", "pinned");
        assertThat(dispatched).containsExactly("kiro-1", "relay-1", "kiro-1", "kiro-1", "relay-1");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:kiro", "anthropic:kiro", "anthropic:relay"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aHeaderForTheRelayTierIsNotHeldByAnInterruptionForAPromptTheRelayRefusedForItsLengthEither(Client client) {
        serve(new Setup());
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");
        ObjectNode compaction = history(client, MODEL, 10, 6_000);
        // This node learns that the relay does not take a prompt of this size; the client is told to try again.
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));
        assertUnavailableToTryAgain(refusedWith(client, compaction.deepCopy(), "session-1"));
        assertServedByKiroDespiteTheHeader(ask(client, compaction.deepCopy(), "session-1"));

        cutAnotherRequestOfTheSession(client, false);

        assertServedByKiroDespiteTheHeader(ask(client, compaction.deepCopy(), "session-1"));
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> {
            assertThat(skip.reason()).isEqualTo(XpSkip.Reason.CONTEXT);
            assertThat(skip.detail()).contains("refused for its length");
        });
        advance(Duration.ofMinutes(2).plusSeconds(1));
        assertServedByKiroDespiteTheHeader(ask(client, compaction.deepCopy(), "session-1"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aSuffixForTheRelayTierIsAnsweredThatThePromptIsTooLongWhateverTheRelayMetInTheSession(Client client) {
        serve(new Setup().window(20_000));
        ObjectNode compaction = pinned(history(client, MODEL, 20, 6_000), "relay");

        assertTooLong(refusedWith(client, compaction.deepCopy(), "session-1"));
        cutAnotherRequestOfTheSession(client, true);

        // Not "try again": the relay is back in two minutes, and the prompt is still too long for it.
        assertTooLong(refusedWith(client, compaction.deepCopy(), "session-1"));
        assertUnavailableToTryAgain(refusedWith(client, pinned(first(client, MODEL, "hello"), "relay"), "session-1"));

        advance(Duration.ofMinutes(2).plusSeconds(1));
        assertTooLong(refusedWith(client, compaction.deepCopy(), "session-1"));
        assertServedBy(ask(client, pinned(first(client, MODEL, "hello"), "relay"), "session-1").response(), "relay", "1", "pinned");
        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(ledger.settled).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aHeaderForARelayTierWhoseRelayIsSwitchedOffIsNotHeldForARequestBeyondTheWindowOfTheRelay(Client client) {
        serve(new Setup().window(20_000).relaysSwitchedOff(true));
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");

        // The relay would not have taken the whole history in any case, so it is no reason to hold it: Kiro serves it.
        assertServedByKiroDespiteTheHeader(ask(client, history(client, MODEL, 20, 6_000), "session-1"));
        assertThat(dispatched).containsExactly("kiro-1");
        // A request it would have taken is held for the relay that is out of service.
        assertUnavailableToTryAgain(refusedWith(client, first(client, MODEL, "hello"), "session-1"));
        assertThat(dispatched).containsExactly("kiro-1");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aHeaderForARelayTierWhoseRelayIsSwitchedOffIsNotHeldForAPromptTheRelayRefusedForItsLengthEither(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.failure(400, "context_length_exceeded"));
        refusedWith(client, pinned(history(client, MODEL, 10, 6_000), "relay"), "session-1");
        assertThat(relays.seen()).hasSize(1);
        // The relay is switched off after it showed what it takes; the node remembers.
        serve(new Setup().relaysSwitchedOff(true));
        extraHeaders.put(TierSelectorParser.REQUEST_HEADER, "relay");

        assertServedByKiroDespiteTheHeader(ask(client, history(client, MODEL, 10, 6_000), "session-1"));
        assertUnavailableToTryAgain(refusedWith(client, first(client, MODEL, "hello"), "session-1"));
        assertThat(relays.seen()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aSuffixForARelayTierWhoseRelayIsSwitchedOffIsAnsweredThatTheTierHasNoAccountWhateverTheSizeOfTheRequest(Client client) {
        serve(new Setup().window(20_000).relaysSwitchedOff(true));

        // The tier holds nothing in service for the suffix, which is the client's explicit demand (design 6.2).
        for (ObjectNode request : List.of(history(client, MODEL, 20, 6_000), first(client, MODEL, "hello"))) {
            ApiException refused = refusedWith(client, pinned(request, "relay"), "session-1");
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED");
        }
        assertThat(dispatched).isEmpty();
    }

    // ---- nobody serves ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aRelayWithoutQuotaAndAKiroThatRefusesTheRequestReleaseTheReservationAndTheUserCarriesOn(Client client) {
        serve(new Setup());
        relays.next("relay-1", Reply.failure(402, "insufficient_quota"));
        nativeAnswers.put("kiro-1", () -> Mono.error(new UpstreamException(400, "Improperly formed request", false)));

        assertThatThrownBy(() -> ask(client, first(client, MODEL, "hello"), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // The relay refused to generate and the second tier refused the request: neither is a reason to hold the money, and
        // not one to lock the user into a review.
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).singleElement().satisfies(release -> assertThat(release.definitive()).isTrue());

        nativeAnswers.clear();
        Turn next = ask(client, first(client, MODEL, "hello again"), "session-2");
        assertServedBy(next.response(), "relay", "1");
        ledger.assertCharge(ledger.settled.getLast(), "anthropic:relay", 1, "1");
    }

    // ---- the pair's gate, and a relay kept for Claude ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Client.class)
    void aKeyThatIsNotOnTheGateIsServedByKiroAndTheRelayIsNeverAsked(Client client) {
        serve(new Setup());
        ApiKeyPrincipal other = new ApiKeyPrincipal("key-2", "test", null, BigDecimal.ZERO);
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(other));
        when(rateLimiter.check("key-2")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));

        Turn turn = ask(client, first(client, MODEL, "hello"), "session-1");

        // The pair is on trial for the keys the administrator listed, and this is not one of them.
        assertKiroServedAfterRelays(turn, 0);
        assertThat(plans.getLast().xpSkips()).singleElement().satisfies(skip -> assertThat(skip.reason()).isEqualTo(XpSkip.Reason.GATE));
    }

    @Test
    void aRelayKeptForClaudeDoesNotServeGlmAndOneThatIsNotDoes() {
        modelCatalog.applyCustomizations(List.of(new ModelCatalogCustomization("glm5.3", "GLM 5.3", null, "GLM", "GLM 5",
            "glm5.3", "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 128000, "GLM", true, null)));
        ProxyAccountEntity glm = account("z-glm-1", "OpenAI-compatible", "api-key");
        glm.setBaseUrl("https://glm.example/v1");
        glm.setAccessToken("sk-glm");
        serve(new Setup().natives(kiro("kiro-1"), glm));
        nativeAnswers.put("z-glm-1", () -> Mono.just(new PlatformCompletionResult("from glm", USAGE)));

        // The relay sorts before the GLM key, so the pool that takes the first would give it GLM if it were not left out.
        for (int i = 0; i < 3; i++) {
            Turn turn = ask(Client.OPENCODE, glmRequest(), "session-1");
            assertThat(turn.answer().text()).isEqualTo("from glm");
            // A vendor without tiers is served as it always was.
            assertThat(turn.response().getHeaders().getFirst(TierResponseHeaders.TIER)).isNull();
        }
        assertThat(dispatched).containsExactly("z-glm-1", "z-glm-1", "z-glm-1");
        assertThat(relays.seen()).isEmpty();
        assertThat(ledger.settled).hasSize(3).allSatisfy(charge -> assertThat(charge.tierRef()).isNull());
        assertThat(ledger.plainDispatches).isEqualTo(3);

        // The relay of a member that is not exclusive serves what compatible keys serve, GLM among it.
        dispatched.clear();
        serve(new Setup().natives(kiro("kiro-1"), glm).exclusive(false));
        assertThat(ask(Client.OPENCODE, glmRequest(), "session-2").answer().text()).isEqualTo("from kiro");
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void withNoOtherKeyGlmHasNoAccountAndTheRelayIsNotAskedAnything() {
        modelCatalog.applyCustomizations(List.of(new ModelCatalogCustomization("glm5.3", "GLM 5.3", null, "GLM", "GLM 5",
            "glm5.3", "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 128000, "GLM", true, null)));
        serve(new Setup());

        assertThatThrownBy(() -> ask(Client.OPENCODE, glmRequest(), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        assertThat(dispatched).isEmpty();
        assertThat(relays.seen()).isEmpty();
        assertThat(ledger.settled).isEmpty();
    }

    // ---- fixtures ----

    /** The tiers, the relays and the native accounts of one scenario. */
    private static final class Setup {
        final List<String> relays = new ArrayList<>(List.of("relay-1"));
        final List<ProxyAccountEntity> natives = new ArrayList<>(List.of(kiro("kiro-1")));
        int window = 200_000;
        /** The window of the relays that do not take the one of the setup. */
        final Map<String, Integer> windows = new HashMap<>();
        boolean reservedSlot = true;
        boolean exclusive = true;
        boolean relaysSwitchedOff;

        Setup relays(String... names) {
            relays.clear();
            relays.addAll(List.of(names));
            return this;
        }

        Setup natives(ProxyAccountEntity... accounts) {
            natives.clear();
            natives.addAll(List.of(accounts));
            return this;
        }

        Setup window(int tokens) {
            this.window = tokens;
            return this;
        }

        Setup windowOf(String relay, int tokens) {
            windows.put(relay, tokens);
            return this;
        }

        /** The relays are registered in their tier and disabled: in the table, in no account cache. */
        Setup relaysSwitchedOff(boolean off) {
            this.relaysSwitchedOff = off;
            return this;
        }

        Setup reservedSlot(boolean on) {
            this.reservedSlot = on;
            return this;
        }

        Setup exclusive(boolean on) {
            this.exclusive = on;
            return this;
        }
    }

    private void advance(Duration time) {
        clock.addAndGet(time.toNanos());
    }

    /** What a request that Kiro serves after {@code relayRequests} asks of the relay is, and what it costs. */
    private void assertKiroServedAfterRelays(Turn turn, int relayRequests) {
        assertServedBy(turn.response(), "kiro", "2");
        assertThat(relays.seen()).hasSize(relayRequests);
        assertThat(dispatched.stream().filter(account -> account.startsWith("relay-")).count()).isEqualTo(relayRequests);
        assertThat(dispatched.getLast()).isEqualTo("kiro-1");
        // The user pays for the tier that served: 0.6 of the standard price, not the price of the tier that was reserved.
        ledger.assertCharged("anthropic:kiro", 2, "0.6");
        assertThat(ledger.settled.getFirst().credits()).isLessThan(ledger.reservedCredits());
        // The attempts that failed without generating are no reason to review or release the reservation.
        assertThat(ledger.released).isEmpty();
        assertThat(ledger.leasedTo.getLast()).isEqualTo("anthropic:kiro");
    }

    private static void assertServedBy(ResponseEntity<?> response, String tierId, String level) {
        assertServedBy(response, tierId, level, "auto");
    }

    private static void assertServedBy(ResponseEntity<?> response, String tierId, String level, String mode) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo(level);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo(mode);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        // Nothing of how the relay is reached is told to the client.
        assertThat(response.getHeaders().toString()).doesNotContain("127.0.0.1", "relay-sonnet", "OPENAI_CHAT");
    }

    // ---- building the world ----

    private void serve(Setup setup) {
        List<ProxyAccountEntity> accounts = new ArrayList<>();
        List<ProxyAccountEntity> relayAccounts = new ArrayList<>();
        for (String name : setup.relays) {
            ProxyAccountEntity relay = account(name, "OpenAI-compatible", "api-key");
            relay.setBaseUrl(relays.url(name));
            relay.setAccessToken("sk-" + name);
            relayAccounts.add(relay);
        }
        if (!setup.relaysSwitchedOff) {
            accounts.addAll(relayAccounts);
        }
        accounts.addAll(setup.natives);
        SourceTierSnapshot snapshot = snapshot(setup, relayAccounts);
        when(registry.snapshot()).thenReturn(snapshot);

        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenAnswer(invocation -> Flux.fromIterable(List.copyOf(accounts)));
        when(repository.findSwitchedOffByKind()).thenAnswer(invocation ->
            Flux.fromIterable(setup.relaysSwitchedOff ? List.copyOf(relayAccounts) : List.<ProxyAccountEntity>of()));
        // The first account the filter takes, so that a scenario knows which of two relays is asked first.
        properties.setAccountSelectionStrategy(ProxyProperties.AccountSelectionStrategy.STICKY);
        accountPool = spy(new AccountPoolService(repository, null, properties, null,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), null, snapshot::exclusiveXpAccountIds));
        accountPool.refreshCache().block();
        doReturn(Mono.empty()).when(accountPool).recordSuccess(anyString(), anyLong(), any());
        doReturn(Mono.empty()).when(accountPool).recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class),
            any(), anyString(), any(AccountPoolRequestContext.class));
        when(registry.exclusiveXpAccountIds()).thenAnswer(invocation -> snapshot.exclusiveXpAccountIds());

        // The registry of this release, not one made for the test: what a pair is offered as is part of what is played.
        DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing,
            bindings, true, true, PairRegistry.defaults(), skipCounts, learnedLimits);
        TierRuntime.Planner recording = new TierRuntime.Planner() {
            @Override
            public Mono<TierPlan> plan(TierPlanRequest request) {
                return planner.plan(request).doOnNext(plans::add);
            }

            @Override
            public TierSelectorParser.Stripped stripSelector(String model, Function<String, TierRuntime.Destination> destination) {
                return planner.stripSelector(model, destination);
            }

            @Override
            public boolean wantsEntryFacts(TierEntry entry) {
                return planner.wantsEntryFacts(entry);
            }
        };
        facade = new ProxyFacade(
            apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings, mock(DisabledAccountScheduledTestService.class),
            modelCatalog, modelMapping, rateLimiter, new KiroPayloadFactory(json, modelCatalog), kiroClient, openAiClient,
            new OpenAiRequestAdapter(json, modelCatalog), new OpenAiResponseFactory(json), new OpenAiResponseStateStore(json),
            new AnthropicRequestAdapter(json, modelCatalog), new AnthropicResponseFactory(json), anthropicClient,
            new GeminiRequestAdapter(json), new GeminiResponseFactory(json), payments, pricing, statsService, admissionStore, ownerStore,
            new TierRuntime(recording, bindings, learnedLimits), new XpDispatcher(RELAY_CLIENT, new SplittableRandom(20261026L)));
    }

    /** Relay first at the standard price, native accounts second at 0.6; the relays map the Sonnet and Opus models, not Haiku. */
    private SourceTierSnapshot snapshot(Setup setup, List<ProxyAccountEntity> relayAccounts) {
        List<Member> members = new ArrayList<>();
        List<MemberModel> models = new ArrayList<>();
        for (ProxyAccountEntity relay : relayAccounts) {
            Member member = new Member("anthropic", relay.getAccountId(), "relay", "OPENAI_CHAT", setup.exclusive, false, false, false,
                false, true, "NONE", 540, true, true);
            members.add(member);
            Map<String, String> wire = Map.of(MODEL, "relay-sonnet", "claude-opus-4.5", "relay-opus");
            int window = setup.windows.getOrDefault(relay.getAccountId(), setup.window);
            wire.forEach((modelId, upstream) -> {
                MemberModel unverified = new MemberModel("anthropic", relay.getAccountId(), modelId, upstream, window, 8192, true, null, null);
                models.add(new MemberModel("anthropic", relay.getAccountId(), modelId, upstream, window, 8192, true,
                    Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, relay)));
            });
        }
        return SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, setup.reservedSlot, 7L)),
            List.of(
                new Tier("anthropic", "relay", 1, "relay", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true, true, null),
                new Tier("anthropic", "kiro", 2, "kiro", new BigDecimal("0.6"),
                    EnumSet.of(AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH), true, true, null)),
            members, models,
            List.of(new PairGate("anthropic", "OPENAI_CHAT", "CHAT", "KEYS", List.of(KEY)),
                new PairGate("anthropic", "OPENAI_CHAT", "MESSAGES", "KEYS", List.of(KEY))));
    }

    private Mono<PlatformCompletionResult> dispatchNative(ProxyAccountEntity account, JsonNode payload) {
        dispatched.add(account.getAccountId());
        List<String> ids = new ArrayList<>();
        collectToolIds(payload, ids);
        nativeCalls.add(new NativeCall(account.getAccountId(), ids, payload.toString()));
        return nativeAnswers.getOrDefault(account.getAccountId(), () -> Mono.just(KIRO_ANSWER)).get();
    }

    /** The ids a request carries for its tool calls, in the order it carries them: what a native tier is sent of them. */
    private static void collectToolIds(JsonNode node, List<String> ids) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                boolean id = (field.getKey().equals("toolUseId") || field.getKey().equals("tool_call_id")) && field.getValue().isString();
                if (id) {
                    ids.add(field.getValue().asString());
                } else if (field.getKey().equals("tool_calls") && field.getValue().isArray()) {
                    field.getValue().forEach(call -> ids.add(call.path("id").asString("")));
                } else {
                    collectToolIds(field.getValue(), ids);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectToolIds(child, ids));
        }
    }

    private static ProxyAccountEntity kiro(String accountId) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc");
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
    }

    private static ProxyAccountEntity anthropicKey(String accountId) {
        ProxyAccountEntity account = account(accountId, "Anthropic", "api-key");
        account.setAccessToken("sk-ant-0000");
        return account;
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setEnabled(true);
        return account;
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }

    // ---- the clients ----

    /** The two clients of the reference scenario, and what each sends and is answered in. */
    private enum Client {
        CLAUDE_CODE("/v1/messages", "x-claude-code-session-id", "Bash"),
        OPENCODE("/v1/chat/completions", "x-opencode-session", "bash");

        private final String path;
        private final String sessionHeader;
        private final String toolName;

        Client(String path, String sessionHeader, String toolName) {
            this.path = path;
            this.sessionHeader = sessionHeader;
            this.toolName = toolName;
        }

        String path() {
            return path;
        }

        boolean messages() {
            return this == CLAUDE_CODE;
        }

        /** The native account that serves the second tier: the Anthropic keys come before Kiro on the Messages route only. */
        String nativeFirst() {
            return messages() ? "anthropic-1" : "kiro-1";
        }

        @Override
        public String toString() {
            return this == CLAUDE_CODE ? "Claude Code" : "OpenCode";
        }
    }

    /** What a client got: the response, and what it says. */
    private record Turn(ResponseEntity<?> response, Answer answer) {
    }

    private record Call(String id, String name, String arguments) {
    }

    private record Answer(String text, List<Call> calls) {
    }

    private ObjectNode first(Client client, String model, String prompt) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model).put("max_tokens", 32000).put("stream", true);
        if (client.messages()) {
            // The first block is Claude Code's billing header, whose hash is new with every request.
            ArrayNode system = body.putArray("system");
            system.addObject().put("type", "text").put("text", BILLING_HEADER);
            system.addObject().put("type", "text")
                .put("text", "You are Claude Code, Anthropic's official CLI for Claude.")
                .putObject("cache_control").put("type", "ephemeral");
            ObjectNode text = body.putArray("messages").addObject().put("role", "user").putArray("content").addObject();
            text.put("type", "text").put("text", prompt).putObject("cache_control").put("type", "ephemeral");
            body.putArray("tools").addObject().put("name", "Bash").put("description", "Runs a shell command")
                .putObject("input_schema").put("type", "object").putObject("properties").putObject("command").put("type", "string");
            body.putObject("metadata").put("user_id",
                "{\"device_id\":\"0000\",\"account_uuid\":\"0000\",\"session_id\":\"00000000-0000-0000-0000-000000000000\"}");
            body.putObject("thinking").put("type", "enabled").put("budget_tokens", 31999);
            body.putObject("context_management").putArray("edits").addObject().put("type", "clear_thinking_20251015").put("keep", "all");
        } else {
            body.putObject("stream_options").put("include_usage", true);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", "You are OpenCode, the best coding agent on the planet.")
                .putObject("cache_control").put("type", "ephemeral");
            messages.addObject().put("role", "user").put("content", prompt).putObject("cache_control").put("type", "ephemeral");
            ObjectNode function = body.putArray("tools").addObject().put("type", "function").putObject("function");
            function.put("name", "bash").put("description", "Runs a shell command");
            function.putObject("parameters").put("type", "object").putObject("properties").putObject("command").put("type", "string");
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /** What the answer of a call is followed by: the assistant's turn as the client keeps it, and the result of its calls. */
    private ObjectNode followUp(Client client, ObjectNode previous, Answer answer, String result) {
        ObjectNode body = previous.deepCopy();
        ArrayNode messages = body.withArray("messages");
        if (client.messages()) {
            ArrayNode blocks = messages.addObject().put("role", "assistant").putArray("content");
            if (!answer.text().isBlank()) {
                blocks.addObject().put("type", "text").put("text", answer.text());
            }
            for (Call call : answer.calls()) {
                ObjectNode block = blocks.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name());
                block.set("input", json.readTree(call.arguments()));
            }
            ArrayNode results = messages.addObject().put("role", "user").putArray("content");
            for (Call call : answer.calls()) {
                results.addObject().put("type", "tool_result").put("tool_use_id", call.id()).put("content", result);
            }
        } else {
            ObjectNode assistant = messages.addObject().put("role", "assistant");
            if (answer.text().isBlank()) {
                assistant.putNull("content");
            } else {
                assistant.put("content", answer.text());
            }
            ArrayNode calls = assistant.putArray("tool_calls");
            for (Call call : answer.calls()) {
                ObjectNode function = calls.addObject().put("id", call.id()).put("type", "function").putObject("function");
                function.put("name", call.name()).put("arguments", call.arguments());
            }
            for (Call call : answer.calls()) {
                messages.addObject().put("role", "tool").put("tool_call_id", call.id()).put("content", result);
            }
        }
        return body;
    }

    /** A request for the tool that searches the web for the model, which Claude Code and OpenCode make as an answer-less call of their own. */
    private ObjectNode webSearch(Client client, String model) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model).put("max_tokens", 32000).put("stream", true);
        if (client.messages()) {
            body.putArray("messages").addObject().put("role", "user").put("content", "Perform a web search for the query: starry");
            body.putArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 8);
        } else {
            body.putObject("stream_options").put("include_usage", true);
            body.putArray("messages").addObject().put("role", "user").put("content", "Perform a web search for the query: starry");
            body.putObject("web_search_options").put("search_context_size", "medium");
        }
        return body;
    }

    /** The request that compacts a conversation: its whole history, {@code pairs} exchanges of {@code size} characters, and an instruction. */
    private ObjectNode history(Client client, String model, int pairs, int size) {
        ObjectNode body = first(client, model, "Let us start");
        ArrayNode messages = body.withArray("messages");
        for (int i = 0; i < pairs; i++) {
            messages.addObject().put("role", "assistant").put("content", filler(size, i));
            messages.addObject().put("role", "user").put("content", filler(size, i + pairs));
        }
        ObjectNode last = (ObjectNode) messages.get(messages.size() - 1);
        last.put("content", last.path("content").asString()
            + "\n\nYour task is to create a detailed summary of the conversation so far.");
        return body;
    }

    private static String filler(int size, int seed) {
        StringBuilder text = new StringBuilder(size);
        for (int i = 0; text.length() < size; i++) {
            text.append("the quick brown fox ").append(seed).append(' ').append(i).append(" jumps over the lazy dog. ");
        }
        return text.substring(0, size);
    }

    private ObjectNode glmRequest() {
        ObjectNode body = json.createObjectNode();
        body.put("model", "glm5.3").put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return body;
    }

    private static String servedTier(Turn turn) {
        return turn.response().getHeaders().getFirst(TierResponseHeaders.TIER);
    }

    private Turn ask(Client client, ObjectNode body, String session) {
        ResponseEntity<?> response = send(client, body, session);
        return new Turn(response, parse(client, response));
    }

    private ResponseEntity<?> send(Client client, ObjectNode body, String session) {
        return start(client, body, session).block(Duration.ofSeconds(60));
    }

    /** The request as the client's connection holds it: it is not made until subscribed to, and ends when disposed of. */
    private Mono<ResponseEntity<?>> start(Client client, ObjectNode body, String session) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post(client.path());
        if (session != null) {
            request.header(client.sessionHeader, session);
        }
        extraHeaders.forEach(request::header);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        lastExchange = exchange;
        return client.messages() ? facade.messages(body, exchange) : facade.chat(body, exchange);
    }

    /** What a client makes of the response: the text and the tool calls, from a body or from the events of a stream. */
    @SuppressWarnings("unchecked")
    private Answer parse(Client client, ResponseEntity<?> response) {
        Object body = response.getBody();
        StringBuilder text = new StringBuilder();
        List<Call> calls = new ArrayList<>();
        if (body instanceof JsonNode node) {
            if (client.messages()) {
                node.path("content").forEach(block -> {
                    if ("text".equals(block.path("type").asString())) {
                        text.append(block.path("text").asString());
                    } else if ("tool_use".equals(block.path("type").asString())) {
                        calls.add(new Call(block.path("id").asString(), block.path("name").asString(), block.path("input").toString()));
                    }
                });
            } else {
                JsonNode message = node.path("choices").path(0).path("message");
                text.append(message.path("content").asString(""));
                message.path("tool_calls").forEach(call -> calls.add(new Call(call.path("id").asString(),
                    call.path("function").path("name").asString(), call.path("function").path("arguments").asString())));
            }
            return new Answer(text.toString(), calls);
        }
        assertThat(body).isInstanceOf(Flux.class);
        List<ServerSentEvent<String>> events = ((Flux<ServerSentEvent<String>>) body).collectList().block(Duration.ofSeconds(60));
        Map<Integer, StringBuilder> arguments = new LinkedHashMap<>();
        Map<Integer, String[]> started = new LinkedHashMap<>();
        for (ServerSentEvent<String> event : events) {
            if (event.data() == null || event.data().equals("[DONE]")) {
                continue;
            }
            JsonNode data = json.readTree(event.data());
            if (client.messages()) {
                int index = data.path("index").asInt();
                switch (data.path("type").asString("")) {
                    case "content_block_start" -> {
                        JsonNode block = data.path("content_block");
                        if ("tool_use".equals(block.path("type").asString())) {
                            started.put(index, new String[] {block.path("id").asString(), block.path("name").asString()});
                            arguments.put(index, new StringBuilder());
                        }
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = data.path("delta");
                        if ("text_delta".equals(delta.path("type").asString())) {
                            text.append(delta.path("text").asString());
                        } else if ("input_json_delta".equals(delta.path("type").asString())) {
                            arguments.get(index).append(delta.path("partial_json").asString());
                        }
                    }
                    default -> {
                        // message_start, content_block_stop, message_delta, message_stop and pings carry no content.
                    }
                }
            } else {
                JsonNode delta = data.path("choices").path(0).path("delta");
                text.append(delta.path("content").asString(""));
                for (JsonNode call : delta.path("tool_calls")) {
                    int index = call.path("index").asInt(0);
                    started.computeIfAbsent(index, ignored -> new String[] {"", ""});
                    arguments.computeIfAbsent(index, ignored -> new StringBuilder());
                    if (call.path("id").isString()) {
                        started.get(index)[0] = call.path("id").asString();
                    }
                    if (call.path("function").path("name").isString()) {
                        started.get(index)[1] = call.path("function").path("name").asString();
                    }
                    arguments.get(index).append(call.path("function").path("arguments").asString(""));
                }
            }
        }
        started.forEach((index, call) -> calls.add(new Call(call[0], call[1], arguments.get(index).length() == 0 ? "{}" : arguments.get(index).toString())));
        return new Answer(text.toString(), calls);
    }

    // ---- the relays ----

    /** One call of a native account, with the tool ids its history carried. */
    /** @param payload the request the account was sent, as the upstream client of its route is handed it */
    private record NativeCall(String account, List<String> toolIds, String payload) {
    }

    // ---- the ledger ----

    /** The billing calls of the facade as the database would have received them. */
    private static final class Ledger {
        final List<Reserved> reserved = new ArrayList<>();
        final List<String> leasedTo = new ArrayList<>();
        final List<Settled> settled = new ArrayList<>();
        final List<Released> released = new ArrayList<>();
        int plainDispatches;

        BigDecimal reservedCredits() {
            return reserved.getLast().credits();
        }

        List<String> holdLevels() {
            List<String> hold = new ArrayList<>();
            reserved.getLast().pricing().path("tierPlan").path("holdLevels").forEach(level -> hold.add(level.asString()));
            return hold;
        }

        /** The one settlement of the request: under this tier, level and factor, for the standard price times the factor. */
        void assertCharged(String tierRef, int level, String factor) {
            assertThat(settled).singleElement().satisfies(charge -> assertCharge(charge, tierRef, level, factor));
        }

        void assertCharge(Settled charge, String tierRef, int level, String factor) {
            assertThat(charge.tierRef()).isEqualTo(tierRef);
            assertThat(charge.level()).isEqualTo(level);
            assertThat(charge.factor()).isEqualByComparingTo(factor);
            // The price of the tier that served for what it reported, and never more than was held for the request.
            assertThat(charge.credits()).isEqualByComparingTo(QUOTE.scaled(new BigDecimal(factor)).cost(USAGE));
        }

        /** Every request that was settled was charged the tier that served it, never above what it reserved. */
        void assertEveryRequestWasChargedTheTierThatServed(List<String> tierRefs) {
            assertThat(settled).extracting(Settled::tierRef).containsExactlyElementsOf(tierRefs);
            assertThat(settled).allSatisfy(charge -> assertThat(charge.credits())
                .isEqualByComparingTo(QUOTE.scaled(charge.factor()).cost(USAGE)));
            for (int i = 0; i < settled.size() && i < reserved.size(); i++) {
                assertThat(settled.get(i).credits()).isLessThanOrEqualTo(reserved.get(i).credits());
            }
        }
    }

    private record Reserved(BigDecimal credits, JsonNode pricing) {
    }

    private record Settled(BigDecimal credits, String tierRef, Integer level, BigDecimal factor) {
    }

    private record Released(boolean definitive, String reason) {
    }
}
