package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionResult;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.GeminiChatConverter;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Limits;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.RequestBounds;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRetryPolicy;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSelectorParser;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierSessionKeys;
import tech.starryapi.proxy.tier.XpLearnedLimits;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * A tiered request whose plan holds a relay, end to end through the entries of the facade and its dispatch loop (design
 * 7.5, 7.8): the reference scenario, a relay as the first tier of the anthropic vendor and Kiro as the second. The planner
 * is the real one, with a registry that offers the Chat Completions and Messages pairs; the dispatcher of the relays is a
 * stand-in that answers per account, and so are Kiro and the account pool. What is looked at is where the request goes,
 * what the relay is asked, what the client is answered, what is charged and how each attempt is recorded.
 */
class ProxyFacadeTieredXpChatTest {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String SESSION_HEADER = "x-claude-code-session-id";
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult KIRO_ANSWER = new PlatformCompletionResult("from kiro", USAGE);
    private static final PlatformCompletionResult OPENAI_ANSWER = new PlatformCompletionResult("from openai", USAGE);
    private static final PlatformCompletionResult RELAY_ANSWER = new PlatformCompletionResult(
        "from the relay", "", List.of(), USAGE, StopReason.END_TURN, UsageSource.REPORTED, List.of("metadata"));
    /** $3/M in, $15/M out: 8,000 in and 1,000 out cost 0.039 at the full price and 0.0234 at 0.6. */
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
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
    private final XpDispatcher xpDispatcher = mock(XpDispatcher.class);
    private final DisabledAccountScheduledTestService scheduledTests = mock(DisabledAccountScheduledTestService.class);
    private final RelayErrorMapper relayErrors = new RelayErrorMapper(objectMapper);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    private final AtomicLong clock = new AtomicLong();
    private final TierSessionBindings bindings = spy(new TierSessionBindings(new ProxyProperties(), clock::get));
    private final XpLearnedLimits learnedLimits = new XpLearnedLimits(clock::get);

    /** The accounts of the pool's cache, in the order it offers them. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** Accounts admission refuses. */
    private final Set<String> busy = new HashSet<>();
    /** What each Kiro account answers; an account that is not listed answers {@link #KIRO_ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> kiroAnswers = new HashMap<>();
    /** What each relay answers; a relay that is not listed answers {@link #RELAY_ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> relayAnswers = new HashMap<>();
    /** The accounts that were dispatched to, relays and Kiro alike, in order. */
    private final List<String> dispatched = new ArrayList<>();
    /** What admission was asked for, in order. */
    private final List<AdmissionRequest> admissions = new ArrayList<>();
    /** The requests the planner made a plan for, as the entries handed them over. */
    private final List<TierPlanRequest> planned = new ArrayList<>();
    private final ProxyAccountEntity relay = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private final ProxyAccountEntity secondRelay = account("relay-2", "OpenAI-compatible", "api-key", "https://relay-two.example/v1");
    /** Never in the cache: a relay that is switched off. */
    private final ProxyAccountEntity thirdRelay = account("relay-3", "OpenAI-compatible", "api-key", "https://relay-three.example/v1");
    private Limits lastLimits;
    private Duration lastTimeLimit;
    private TierDispatch lastDispatch;

    @BeforeEach
    void standInForTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59))));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class)))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59))));
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.empty());
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(RoutingPolicySettings.defaults()));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        when(accountPool.recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class))).thenReturn(Mono.empty());
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenReturn(true);
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
            // As the real selection does, an account that is out of quota is not offered.
            Optional<ProxyAccountEntity> found = cached.stream().filter(filter).filter(accountPool::isUsable).findFirst();
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        when(accountPool.hasSelectableAccount(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return Mono.just(cached.stream().anyMatch(filter));
        });
        when(accountPool.refreshCacheIfStale()).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AdmissionRequest request = invocation.getArgument(0);
            admissions.add(request);
            return Mono.just(busy.contains(request.accountId())
                ? new AdmissionDecision(false, null, "ACCOUNT_BUSY", null, null)
                : new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600)));
        });
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            dispatched.add(account.getAccountId());
            return kiroAnswers.getOrDefault(account.getAccountId(), () -> Mono.just(KIRO_ANSWER)).get();
        });
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            dispatched.add(account.getAccountId());
            return Mono.just(OPENAI_ANSWER);
        });
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            dispatched.add(account.getAccountId());
            return Mono.just(OPENAI_ANSWER);
        });
        when(xpDispatcher.dispatch(any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            lastDispatch = invocation.getArgument(1);
            lastLimits = invocation.getArgument(2);
            lastTimeLimit = invocation.getArgument(3);
            dispatched.add(account.getAccountId());
            return relayAnswers.getOrDefault(account.getAccountId(), () -> Mono.just(RELAY_ANSWER)).get();
        });
    }

    // ---- the relay serves ----

    @Test
    void aChatRequestIsServedByTheRelayAndAnsweredLikeAnyOther() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = (JsonNode) response.getBody();
        assertThat(body.path("choices").path(0).path("message").path("content").asString()).isEqualTo("from the relay");
        // The catalog model is echoed, not the name the relay was asked for.
        assertThat(body.path("model").asString()).isEqualTo("claude-sonnet-4.5");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        assertThat(response.getHeaders().toString()).doesNotContain("relay.example", "relay-sonnet", "OPENAI_CHAT");
        assertThat(dispatched).containsExactly("relay-1");
        verify(kiroClient, never()).complete(any(), any(), anyString());
    }

    @Test
    void theRelayIsSentThePlansBodyUnderTheNameOfItsMappingWithTheLimitsOfItsTier() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        chat(chatBody());

        assertThat(lastDispatch.kind()).isEqualTo(TierDispatch.Kind.OPENAI_CHAT);
        assertThat(lastDispatch.wireModel()).isEqualTo("relay-sonnet");
        assertThat(lastDispatch.modelId()).isEqualTo("claude-sonnet-4.5");
        // What the tier reserved: the input of the body the relay is sent, and the output limit the body forwards.
        assertThat(lastLimits.maxOutputTokens()).isEqualTo(4096);
        assertThat(lastLimits.maxInputTokens()).isEqualTo(
            RequestBounds.xpInputTokens(lastDispatch.wireBody(), 8192));
        // The member's own time limit, with the lease of the reservation far from its end.
        assertThat(lastTimeLimit).isEqualTo(Duration.ofSeconds(540));
    }

    @Test
    void aRelayIsHeldToTheOutputLimitItWasSentAndNotToTheBoundTheTierReservedFor() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1"), gate("MESSAGES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode small = chatBody();
        small.put("max_tokens", 100);

        chat(small);

        // The reservation takes the default limit of 4096 for a request that asks for fewer, which is no reason to pay a
        // relay for more than it was told it may write.
        assertThat(lastDispatch.wireBody().path("max_tokens").asInt()).isEqualTo(100);
        assertThat(lastLimits.maxOutputTokens()).isEqualTo(100);

        ObjectNode asked = messagesBody();
        asked.put("max_tokens", 100);
        messages(asked);

        assertThat(lastLimits.maxOutputTokens()).isEqualTo(100);
    }

    @Test
    void theLeaseOfARelayDispatchIsMadeForTheTimeTheRelayIsGiven() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        chat(chatBody());

        verify(apiKeyService).markUsageDispatchedTiered(any(), anyString(), eq("anthropic:relay"), eq(Duration.ofSeconds(540)));
        // A relay is leased through the call that knows its time limit, and no other dispatch is.
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString());
    }

    @Test
    void aRelayIsAdmittedAndCooledDownUnderTheModelOfItsMappingAndItsOwnEndpoint() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        chat(chatBody());

        // The mapping's model, not the one the client wrote, and not the path of the client's request.
        assertThat(admissions).hasSize(1);
        assertThat(admissions.getFirst().accountId()).isEqualTo("relay-1");
        assertThat(admissions.getFirst().modelScope()).isEqualTo("claude-sonnet-4.5");
        assertThat(admissions.getFirst().endpointScope()).isEqualTo("xp:/chat/completions");
        ArgumentCaptor<AdmissionResult> results = ArgumentCaptor.forClass(AdmissionResult.class);
        verify(admissionStore).reconcile(results.capture());
        assertThat(results.getValue().success()).isTrue();
        assertThat(results.getValue().evidence()).containsEntry("route", "OPENAI_CHAT");
    }

    @Test
    void anAliasOfTheModelIsAdmittedAndCooledDownUnderTheModelOfTheMappingToo() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "x"), null, "relay-1", "sk-secret"));
        ObjectNode body = chatBody();
        body.put("model", "sonnet");

        chat(body);

        // Whatever the client calls the model, the relay's "not served" answer is kept under the model of its mapping, so
        // that the next alias does not meet the same 404 once more.
        assertThat(admissions.getFirst().accountId()).isEqualTo("relay-1");
        assertThat(admissions.getFirst().modelScope()).isEqualTo("claude-sonnet-4.5");
        AdmissionResult failed = reconciled().stream().filter(result -> !result.success()).findFirst().orElseThrow();
        assertThat(failed.evidence()).containsEntry("upstreamModel", "claude-sonnet-4.5");
        // The kiro attempt keeps the model of the pool context as it always has.
        assertThat(admissions.get(1).accountId()).isEqualTo("kiro-1");
        assertThat(admissions.get(1).modelScope()).isEqualTo("sonnet");
        assertThat(admissions.get(1).endpointScope()).isEqualTo(CHAT_PATH);
    }

    @Test
    void aRelaysInFlightTokensAreCountedFromTheBodyItIsSent() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        chat(chatBody());

        long fromTheWireBody = Math.max(1, Math.min(1_000_000, (JsonNodes.charLength(lastDispatch.wireBody()) + 3L) / 4L));
        assertThat(admissions.getFirst().reservedInputTokens()).isEqualTo(fromTheWireBody);
        assertThat(admissions.getFirst().reservedOutputTokens()).isEqualTo(4096);
    }

    @Test
    void aMessagesRequestIsServedByTheRelayAndItsToolCallIsRenderedUnderTheIdItWasGiven() {
        configure(owner(relayMember("relay-1"), gate("MESSAGES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        PlatformToolUse call = new PlatformToolUse("toolu_ABCDEFGHIJKLMNOPQRSTUVWX", "Bash", objectMapper.createObjectNode().put("command", "ls"),
            "function", "{\"command\":\"ls\"}", null);
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult(
            "", "", List.of(call), USAGE, StopReason.TOOL_USE, UsageSource.REPORTED, List.of())));

        ResponseEntity<?> response = facade().messages(messagesBody(), exchange(MESSAGES_PATH, null)).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode content = ((JsonNode) response.getBody()).path("content");
        assertThat(content.path(0).path("type").asString()).isEqualTo("tool_use");
        assertThat(content.path(0).path("id").asString()).isEqualTo("toolu_ABCDEFGHIJKLMNOPQRSTUVWX");
        assertThat(((JsonNode) response.getBody()).path("stop_reason").asString()).isEqualTo("tool_use");
        assertThat(lastDispatch.pair().entry()).isEqualTo(Entry.MESSAGES);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    @Test
    void aStreamingClientIsAnsweredWithTheRelaysAnswerAsAStreamThatCarriesTheHeaders() {
        configure(owner(relayMember("relay-1"), gate("MESSAGES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode body = messagesBody();
        body.put("stream", true);
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult(
            "cut off", "", List.of(), USAGE, StopReason.MAX_TOKENS, UsageSource.REPORTED, List.of())));

        ResponseEntity<?> response = facade().messages(body, exchange(MESSAGES_PATH, null)).block();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        // The relay's reason for stopping reaches the client in the words of its own protocol.
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> stream = (Flux<ServerSentEvent<String>>) response.getBody();
        String events = String.join("", stream.map(event -> event.data() == null ? "" : event.data()).collectList().block());
        assertThat(events).contains("cut off").contains("max_tokens");
    }

    @Test
    void theRelayIsChargedThePriceOfItsTierAndTheRequestLogSaysHowItWasServed() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        chat(chatBody());

        // 8,000 in and 1,000 out at the full price of the relay's tier, factor 1.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.039")) == 0), eq(8_000L), eq(1_000L), eq("anthropic:relay"), eq(1),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(BigDecimal.ONE) == 0));
        StatsService.RequestLog row = onlyLog();
        assertThat(row.success()).isTrue();
        assertThat(row.accountId()).isEqualTo("relay-1");
        assertThat(row.sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(row.sourceTierLevel()).isEqualTo(1);
        assertThat(row.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(row.wireModel()).isEqualTo("relay-sonnet");
        assertThat(row.usageSource()).isEqualTo("REPORTED");
        assertThat(row.stopReason()).isEqualTo("END_TURN");
        assertThat(row.degradedFeatures()).isNotNull().isNotBlank();
        // What the row says about the model and the path is what it always said.
        assertThat(row.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(row.path()).isEqualTo(CHAT_PATH);
        assertThat(row.upstreamModel()).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void aRelayIsNeverChargedAboveWhatItsTierReserved() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        // A usage far beyond the bounds the dispatcher applies would not be believed: the estimate is the ceiling.
        PlatformUsage huge = new PlatformUsage(900_000_000L, 900_000_000L, BigDecimal.ZERO, 0, 0, 0);
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult(
            "x", "", List.of(), huge, StopReason.END_TURN, UsageSource.CLAMPED, List.of())));

        chat(chatBody());

        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(any(), anyString(), credits.capture(), anyLong(), anyLong(), eq("anthropic:relay"), eq(1), any());
        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).reserveUsage(any(), anyString(), reserved.capture(), anyString());
        assertThat(credits.getValue()).isLessThanOrEqualTo(reserved.getValue());
    }

    // ---- the relay cannot serve, and the request goes on to Kiro ----

    @Test
    void aRelayThatDoesNotServeTheModelIsLeftForKiroAndCooledDownByTheMapping() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "no such model"), null, "relay-1", "sk-secret"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("2");
        // Billed at the tier that served: 0.039 at 0.6.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.0234")) == 0), eq(8_000L), eq(1_000L), eq("anthropic:kiro"), eq(2),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(new BigDecimal("0.6")) == 0));
        // The unbilled failure of the first attempt is no reason to review the reservation.
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        // The relay says it does not serve the model: for the model of the mapping, on the relay's endpoint, for ten minutes.
        AdmissionResult failed = reconciled().stream().filter(result -> !result.success()).findFirst().orElseThrow();
        assertThat(failed.failureScope()).isEqualTo(AccountFailureScope.CAPACITY_MODEL);
        assertThat(failed.cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(failed.evidence()).containsEntry("upstreamModel", "claude-sonnet-4.5").containsEntry("endpoint", "xp:/chat/completions");
        // The failed attempt is logged at its own tier, with how it was sent.
        StatsService.RequestLog first = logs().getFirst();
        assertThat(first.success()).isFalse();
        assertThat(first.sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(first.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(first.wireModel()).isEqualTo("relay-sonnet");
        assertThat(first.usageSource()).isNull();
        // The kind of failure is what the status page counts the attempts of a pair by.
        assertThat(first.stopReason()).isEqualTo("MODEL_NOT_SERVED");
        // Nothing of the relay's words is in what the log or the client keeps.
        assertThat(first.errorMessage()).doesNotContain("no such model");
    }

    @Test
    void anErrorInsideAnAnswerThatWasSuccessfulOnTheWireIsLeftForKiroToo() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode inBand = objectMapper.createObjectNode().put("message", "You exceeded your current quota").put("type", "insufficient_quota");
        fails(relayAnswers, "relay-1", relayErrors.fromInBand(inBand, "relay-1", "sk-secret"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        // The relay refused to serve, and nothing was generated: the reservation is not left for review.
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        AdmissionResult failed = reconciled().stream().filter(result -> !result.success()).findFirst().orElseThrow();
        assertThat(failed.statusCode()).isEqualTo(402);
    }

    @Test
    void aBusyRelayIsLeftForKiroWithoutBeingAsked() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");

        ResponseEntity<?> response = chat(chatBody());

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(dispatched).containsExactly("kiro-1");
        verify(xpDispatcher, never()).dispatch(any(), any(), any(), any(), anyBoolean());
        // The refusal was asked under the relay's keys all the same.
        assertThat(admissions.getFirst().accountId()).isEqualTo("relay-1");
        assertThat(admissions.getFirst().endpointScope()).isEqualTo("xp:/chat/completions");
    }

    @Test
    void aRelayThatDoesNotServeTheModelLeavesTheTierToItsNextRelayBeforeTheNextTier() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "x"), null, "relay-1", "sk-secret"));

        ResponseEntity<?> response = chat(chatBody());

        // The tier is used up before the next one is: the second relay serves, and Kiro is never asked.
        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    // ---- the relay turns its own key down ----

    /** Statuses and the words of the three ways a relay says it will not take the key of its account. */
    private UpstreamException relayRejectsItsKey(String how) {
        return switch (how) {
            case "401" -> relayErrors.fromResponse(401, error("invalid_api_key", "Incorrect API key provided: sk-secret"), null, "relay-1", "sk-secret");
            case "403" -> relayErrors.fromResponse(403, error("permission_error", "Your account may not use this endpoint"), null, "relay-1", "sk-secret");
            default -> relayErrors.fromInBand(
                objectMapper.createObjectNode().put("message", "invalid x-api-key").put("type", "authentication_error"), "relay-1", "sk-secret");
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"401", "403", "in-band"})
    void aRelayThatRejectsItsOwnKeyIsLeftForKiroAndRestsForTenMinutes(String how) {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayRejectsItsKey(how));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("2");
        // Billed at the tier that served: 8,000 in and 1,000 out cost 0.039 at the standard price and 0.0234 at 0.6.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.0234")) == 0), eq(8_000L), eq(1_000L), eq("anthropic:kiro"), eq(2),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(new BigDecimal("0.6")) == 0));
        // A refusal given before anything was generated is no reason to review the reservation.
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        // The account rests as a native account does for credentials that are bad, and is not disabled by a relay's word.
        AdmissionResult failed = reconciled().stream().filter(result -> !result.success()).findFirst().orElseThrow();
        assertThat(failed.failureScope()).isEqualTo(AccountFailureScope.CREDENTIAL);
        assertThat(failed.cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(failed.permanentCredentialFailure()).isFalse();
        assertThat(failed.statusCode()).isEqualTo(how.equals("403") ? 403 : 401);
        // The failed attempt is logged at its own tier with the kind of failure, and nothing the relay wrote.
        StatsService.RequestLog first = logs().getFirst();
        assertThat(first.success()).isFalse();
        assertThat(first.sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(first.stopReason()).isEqualTo("KEY_REFUSED");
        assertThat(first.errorMessage()).doesNotContain("sk-secret", "Incorrect API key", "invalid x-api-key", "may not use");
        // The cooldown is the account's, whatever the model: it is kept under the relay's own endpoint.
        assertThat(failed.evidence()).containsEntry("endpoint", "xp:/chat/completions");
    }

    @Test
    void aRelayThatRejectsItsOwnKeyLeavesTheTierToItsNextRelayBeforeTheNextTier() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayRejectsItsKey("401"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(dispatched).containsExactly("relay-1", "relay-2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    @Test
    void aRelayThatRejectsItsOwnKeyFallsToKiroInOneRequestAndTheSessionIsPushedDownOnlyWhenTheTierHadNothingElse() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayRejectsItsKey("401"));

        chat(chatBody(), "session-1");

        // The tier had no other account to give: as for an account that is busy, this is a fall of the session (design 4.7).
        verify(bindings).downgrade(any(), any(), any());
    }

    @Test
    void aRelayThatRejectsItsOwnKeyWithNothingNativeBelowIsNotToldToTheClientAs401() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay);
        fails(relayAnswers, "relay-1", relayRejectsItsKey("401"));

        assertThatThrownBy(() -> chat(chatBody()))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
                assertThat(error.getMessage()).doesNotContain("Source error", "401", "invalid_api_key");
            });

        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
    }

    @Test
    void aRelayThatRejectsItsOwnKeyWhileKiroIsBusyIsNotToldToTheClientAs403() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("kiro-1");
        fails(relayAnswers, "relay-1", relayRejectsItsKey("403"));

        assertThatThrownBy(() -> chat(chatBody()))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getMessage()).doesNotContain("Source error", "403", "permission_error");
            });

        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aRequestPinnedToTheRelayIsToldToTryAgainWhenTheRelayRejectsItsKey() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayRejectsItsKey("401"));
        ObjectNode body = chatBody();
        body.put("model", "claude-sonnet-4.5@relay");
        MockServerWebExchange exchange = exchange(CHAT_PATH, null);

        assertThatThrownBy(() -> facade().chat(body, exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
            });

        // No other tier may serve it, at another price, and what the relay said is not the answer.
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void theRejectionOfANativeAccountIsAnsweredAsItAlwaysWas() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        fails(kiroAnswers, "kiro-1", new UpstreamException(401, "The bearer token is invalid", false));

        assertThatThrownBy(() -> chat(chatBody()))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(error.getCode()).isEqualTo("UPSTREAM_ERROR");
            });

        assertThat(dispatched).containsExactly("kiro-1");
    }

    // ---- the relay refuses the request itself ----

    @Test
    void aConversionTheRelayRefusedSendsTheRequestOnceToANativeAccountAndPushesNobodyDown() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400, error("invalid_request_error", "Unsupported parameter: x"), null, "relay-1", "sk-secret"));

        ResponseEntity<?> response = chat(chatBody(), "session-1");

        // The second relay would be asked the same: the request goes straight to the native accounts below.
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        AdmissionResult failed = reconciled().stream().filter(result -> !result.success()).findFirst().orElseThrow();
        // The relay itself is not at fault: no cooldown for the account.
        assertThat(failed.failureScope()).isEqualTo(AccountFailureScope.REQUEST);
        assertThat(failed.cooldown()).isEqualTo(Duration.ZERO);
        // The refused attempt is counted as such; the one that Kiro answered says nothing of the kind.
        assertThat(logs().getFirst().stopReason()).isEqualTo("CONVERSION_REJECTED");
        assertThat(logs().getLast().stopReason()).as("Kiro's answer is not a relay's attempt").isNull();

        // The crossing was for the request's sake: the session is not kept in the second tier, and the next request of it
        // goes to the relay first, as the first one did.
        dispatched.clear();
        kiroAnswers.clear();
        relayAnswers.clear();
        ResponseEntity<?> next = chat(chatBody(), "session-1");
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(next.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    @Test
    void aPromptTheRelayCouldNotTakeIsAnsweredByKiroInstead() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "This model's maximum context length is 8192 tokens"), null, "relay-1", "sk-secret"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        assertThat(logs().getFirst().stopReason()).as("the window is not a conversion the relay refused").isEqualTo("CONTEXT_EXCEEDED");
    }

    @Test
    void theCrossingGoesToNativeAccountsOnlyAndNoRelayOfALowerTierIsAskedTheSame() {
        // The second tier holds a relay too, which would be asked the same, and no native account is available.
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2", "kiro")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay);
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));

        assertThatThrownBy(() -> chat(chatBody()))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                // Said so that a client shortens what it sends; the relay's words are not passed on.
                assertThat(error.getMessage()).startsWith("prompt is too long").doesNotContain("8192", "maximum context");
            });

        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aRefusalOfTheOnlyTierIsAnswered400AndReleasesTheReservation() {
        configure(SourceTierSnapshot.of(7L, List.of(vendor()), List.of(tier("relay", 1, "1")),
            List.of(relayMember("relay-1")), List.of(mapping(relayMember("relay-1"), relay)), List.of(gate("CHAT", "KEYS", "key-1"))));
        add(relay);
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));

        assertThatThrownBy(() -> chat(chatBody()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getMessage()).startsWith("prompt is too long"));

        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    // ---- a relay that refused the request itself, with native accounts that cannot take it for now (design 7.8) ----

    private void relayRefusesTheLengthOfEveryPrompt() {
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));
    }

    private ApiException refusedWith(ObjectNode body, MockServerWebExchange exchange) {
        return refusedWith(facade(), body, exchange);
    }

    private ApiException refusedWith(ProxyFacade facade, ObjectNode body, MockServerWebExchange exchange) {
        try {
            facade.chat(body, exchange).block();
        } catch (ApiException refused) {
            return refused;
        }
        throw new AssertionError("The request was not refused");
    }

    @Test
    void aPromptTheRelayRefusedIsNotAnsweredTooLongWhenKiroWouldTakeItAndIsOnlyBusy() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("kiro-1");
        relayRefusesTheLengthOfEveryPrompt();

        // Shortening the conversation would be lossy and needless: Kiro takes the prompt as it is, and is busy for now.
        ApiException first = refusedWith(conversationOf(4_000), exchange(CHAT_PATH, null));
        ApiException again = refusedWith(conversationOf(4_000), exchange(CHAT_PATH, null));

        assertThat(first.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        // The identical request is answered the same, the relay now being left out of its plan.
        assertThat(again.getStatus()).isEqualTo(first.getStatus());
        assertThat(again.getCode()).isEqualTo(first.getCode());
        assertThat(dispatched).containsExactly("relay-1");
        // The relay had the request until the end: nothing is left for review.
        verify(apiKeyService, times(1)).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());

        busy.clear();
        assertThat(chat(conversationOf(4_000)).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
    }

    @Test
    void aPromptTheRelayRefusedIsNotAnsweredTooLongWhenKiroWouldTakeItAndIsOutOfQuota() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        ProxyAccountEntity exhausted = kiro("kiro-1");
        add(relay, exhausted);
        // Out of quota, so that the plan holds the relay's tier alone and the relay has no tier below it to cross to.
        when(accountPool.isUsable(exhausted)).thenReturn(false);
        relayRefusesTheLengthOfEveryPrompt();

        ApiException first = refusedWith(conversationOf(4_000), exchange(CHAT_PATH, null));
        ApiException again = refusedWith(conversationOf(4_000), exchange(CHAT_PATH, null));

        assertThat(first.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(first.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(again.getCode()).isEqualTo(first.getCode());
        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService, times(1)).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aConversionTheRelayRefusedIsNotAnsweredWithItsErrorWhenKiroIsOnlyBusyEither() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("kiro-1");
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400, error("invalid_request_error", "Unsupported parameter: x"),
            null, "relay-1", "sk-secret"));

        ApiException refused = refusedWith(chatBody(), exchange(CHAT_PATH, null));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    /** The tier holds Kiro and the relay, Kiro first, and the tier below holds a second Kiro account. */
    private void kiroAndTheRelayShareTheFirstTier() {
        configure(SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
                tier("mixed", 1, "1", AccountAllocationCategory.KIRO),
                tier("oauth", 2, "0.6", AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(relayMember("relay-1", "mixed")), List.of(mapping(relayMember("relay-1", "mixed"), relay)),
            List.of(gate("CHAT", "KEYS", "key-1"))));
        add(kiro("kiro-1"), relay);
    }

    @ParameterizedTest(name = "request {0} seconds old")
    @ValueSource(longs = {0, 250})
    void aPromptTheRelayRefusedAfterAKiroFailureThatMayHaveBeenBilledIsAnsweredAsItIsWhateverTheRequestsAge(long seconds) {
        kiroAndTheRelayShareTheFirstTier();
        // The timeout may have been billed: the request is narrowed to its tier, and the relay of the tier is asked next.
        fails(kiroAnswers, "kiro-1", new UpstreamException(408, "Request timeout", true));
        relayRefusesTheLengthOfEveryPrompt();

        ApiException refused = refusedWith(facade(offering(), Duration.ofSeconds(seconds)), conversationOf(4_000), exchange(CHAT_PATH, null));

        // Not told to try again: the request would be reserved and charged a second time for what Kiro may have generated.
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getMessage()).startsWith("prompt is too long");
        assertThat(dispatched).containsExactly("kiro-1", "relay-1");
        verify(apiKeyService, times(1)).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aPromptTheRelayRefusedOfARequestOlderThanTheDeadlineIsToldToTryAgainWhileKiroIsOnlyBusy() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("kiro-1");
        relayRefusesTheLengthOfEveryPrompt();

        ApiException refused = refusedWith(facade(offering(), TierRetryPolicy.CROSS_TIER_DEADLINE.plusSeconds(1)),
            conversationOf(4_000), exchange(CHAT_PATH, null));

        // Nothing about the request is uncertain, and the identical request, made again, crosses to Kiro.
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService, times(1)).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aPromptTheRelayRefusedIsStillAnsweredTooLongWhenNoNativeAccountIsThereAtAll() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay);
        relayRefusesTheLengthOfEveryPrompt();

        // Kiro has no account, busy or not: the prompt is too long for everything there is, and a client shortens it.
        ApiException refused = refusedWith(conversationOf(4_000), exchange(CHAT_PATH, null));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getMessage()).startsWith("prompt is too long");
    }

    @Test
    void aPromptTheRelayRefusedIsAnsweredTooLongToARequestThatNamedTheTierBySuffixWhateverKiroIs() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayRefusesTheLengthOfEveryPrompt();
        ObjectNode body = conversationOf(4_000);
        body.put("model", "claude-sonnet-4.5@relay");

        // The suffix is the client's demand for this tier, and Kiro may not serve it: the same answer every time.
        ApiException first = refusedWith(body.deepCopy(), exchange(CHAT_PATH, null));
        ApiException again = refusedWith(body.deepCopy(), exchange(CHAT_PATH, null));

        assertThat(first.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(first.getMessage()).startsWith("prompt is too long");
        assertThat(again.getStatus()).isEqualTo(first.getStatus());
        assertThat(again.getMessage()).isEqualTo(first.getMessage());
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void aPromptTheRelayRefusedIsAnsweredWithATryAgainToARequestWhoseHeaderNamedTheTierAndTheRetryDropsTheHint() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayRefusesTheLengthOfEveryPrompt();
        MockServerWebExchange named = MockServerWebExchange.from(
            MockServerHttpRequest.post(CHAT_PATH).header(TierSelectorParser.REQUEST_HEADER, "relay"));

        // A hint gives way: this node now knows the relay does not take the prompt, and the retry is served by Kiro, which
        // the client is not made to shorten the conversation for.
        ApiException refused = refusedWith(conversationOf(4_000), named);

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
        assertThat(named.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());

        MockServerWebExchange retried = MockServerWebExchange.from(
            MockServerHttpRequest.post(CHAT_PATH).header(TierSelectorParser.REQUEST_HEADER, "relay"));
        ResponseEntity<?> retry = facade().chat(conversationOf(4_000), retried).block();
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
    }

    @Test
    void aConversionTheRelayRefusedIsAnsweredWithItsErrorToARequestWhoseHeaderNamedTheTier() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400, error("invalid_request_error", "Unsupported parameter: x"),
            null, "relay-1", "sk-secret"));
        MockServerWebExchange named = MockServerWebExchange.from(
            MockServerHttpRequest.post(CHAT_PATH).header(TierSelectorParser.REQUEST_HEADER, "relay"));

        // Nothing is learned of a conversion the relay will not take: a retry would be refused the same, so none is invited.
        ApiException refused = refusedWith(chatBody(), named);

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(named.getResponse().getHeaders().getFirst("Retry-After")).isNull();
        assertThat(dispatched).containsExactly("relay-1");
    }

    /**
     * Anthropic says a prompt does not fit beside max_tokens in words of its own, which a relay in front of it passes on. The Chat
     * entry has no output limit that the gateway chose, so the refusal teaches this node nothing and is any other 400 of the
     * converted request: a request whose header named the tier is told the relay's error and not invited to try again, and a
     * request with nowhere native to go is not told the prompt is too long.
     */
    @Test
    void aPromptTheRelayRefusedBesideTheClientsOutputInTheWordsOfAnthropicIsAConversionItWillNotTakeAndTeachesNothing() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400, error(null, "input length and `max_tokens` exceed context limit: "
            + "188240 + 21333 > 200000, decrease input length or `max_tokens` and try again"), null, "relay-1", "sk-secret"));
        MockServerWebExchange named = MockServerWebExchange.from(
            MockServerHttpRequest.post(CHAT_PATH).header(TierSelectorParser.REQUEST_HEADER, "relay"));

        ApiException refused = refusedWith(chatBody(), named);
        ApiException again = refusedWith(chatBody(), MockServerWebExchange.from(
            MockServerHttpRequest.post(CHAT_PATH).header(TierSelectorParser.REQUEST_HEADER, "relay")));

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(refused.getMessage()).startsWith("Source error 400").doesNotContain("prompt is too long");
        assertThat(named.getResponse().getHeaders().getFirst("Retry-After")).isNull();
        assertThat(again.getMessage()).isEqualTo(refused.getMessage());
        // Nothing was learned, so the relay is asked again and gives the same answer.
        assertThat(dispatched).containsExactly("relay-1", "relay-1");
    }

    @Test
    void aRelayThatCouldNotBeReachedIsLeftForKiroAndNothingIsLeftForReview() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        // The connect timeout of the transport names the host and the address; the dispatcher hands it on in a fixed text.
        fails(relayAnswers, "relay-1", UpstreamFailureClassifier.unreached(
            new java.net.ConnectException("Connection refused: relay.example/203.0.113.7:443"), relay));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        assertThat(logs().getFirst().errorMessage()).doesNotContain("relay.example", "203.0.113.7");
        assertThat(logs().getFirst().stopReason()).as("a failure of no known kind says nothing").isNull();
    }

    // ---- the relay may have generated ----

    @Test
    void anInterruptedRelayIsNotRetriedRetestedOrClimbedBackToAndTheReservationIsReleasedAsUnbilled() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> chat(chatBody(), "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // It may be generating the answer: nothing else is asked, whatever else is on offer.
        assertThat(dispatched).containsExactly("relay-1");
        verify(kiroClient, never()).complete(any(), any(), anyString());
        // The operator carries the loss: the reservation is released for good, not left for review.
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        // No second round of selection, so no reload of the account cache and no retest of the accounts that are out of service.
        verify(accountPool, never()).refreshCacheIfStale();
        verifyNoInteractions(scheduledTests);
        // The relay rests for a minute, as for any failure of the transport.
        AdmissionResult failed = reconciled().getFirst();
        assertThat(failed.success()).isFalse();
        assertThat(failed.failureScope()).isEqualTo(AccountFailureScope.TRANSPORT);
        assertThat(failed.cooldown()).isEqualTo(Duration.ofMinutes(1));
        assertThat(logs().getFirst().sourceDispatch()).isEqualTo("OPENAI_CHAT");
        // The log says it was broken off after the relay may have generated: the status page's UNBILLED.
        assertThat(logs().getFirst().stopReason()).isEqualTo("INTERRUPTED");
    }

    // ---- the reservation of a relay's attempt (design 7.8, invariant I7) ----

    @Test
    void aClientThatGivesUpWhileTheRelayIsAskedReleasesTheReservationForGood() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", Mono::never);

        Disposable request = facade().chat(chatBody(), exchange(CHAT_PATH, null)).subscribe();
        assertThat(dispatched).containsExactly("relay-1");
        request.dispose();

        // The relay may be generating the answer and nobody will be charged for it: the operator carries it, and the
        // reservation does not wait for a review.
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), eq("Client disconnected; cross-protocol attempt released"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void aClientThatGivesUpAfterTheRelayAnsweredAndBeforeTheSettlementReturnedReleasesTheReservationForGood() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.never());

        Disposable request = facade().chat(chatBody(), exchange(CHAT_PATH, null)).subscribe();
        // The answer is in and its settlement is on its way: the attempt is still the relay's until that has returned.
        verify(apiKeyService).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), eq("anthropic:relay"), eq(1), any());
        request.dispose();

        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), eq("Client disconnected; cross-protocol attempt released"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
    }

    @Test
    void aClientThatGivesUpWhileKiroIsAskedAfterARelayFailedLeavesTheReservationForReviewAsItAlwaysDid() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "x"), null, "relay-1", "sk-secret"));
        kiroAnswers.put("kiro-1", Mono::never);

        Disposable request = facade().chat(chatBody(), exchange(CHAT_PATH, null)).subscribe();
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        request.dispose();

        // Kiro has the request now, and what it does with it is not known: a native attempt is not the operator's loss.
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), eq("Client disconnected after dispatch"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aClientThatGivesUpWhileKiroIsAskedInsteadOfARelayThatIsOffTheGateLeavesTheReservationForReview() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "OFF")));
        add(relay, kiro("kiro-1"));
        kiroAnswers.put("kiro-1", Mono::never);

        Disposable request = facade().chat(chatBody(), exchange(CHAT_PATH, null)).subscribe();
        assertThat(dispatched).containsExactly("kiro-1");
        request.dispose();

        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), eq("Client disconnected after dispatch"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aSettlementThatFailsForARelaysAnswerReleasesTheReservationForGoodAndForKirosAnswerStaysForReview() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.error(new IllegalStateException("the database went away")));

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(IllegalStateException.class);

        // The relay generated the answer and nothing could be charged for it: the operator carries that too.
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), eq("Request failed"));

        // The same failure after a native answer is what it always was: the usage may be owed, and a person looks at it.
        org.mockito.Mockito.clearInvocations(apiKeyService);
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "x"), null, "relay-1", "sk-secret"));
        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(IllegalStateException.class);
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), eq("Request failed"));
    }

    @Test
    void anAttemptAtARelayAfterANativeOneThatMayHaveBeenBilledDoesNotMakeTheReservationFreeToRelease() {
        // Kiro and the relay share the first tier, so that the relay is asked after Kiro was, within the tier.
        Member member = relayMember("relay-1");
        configure(SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
            tier("relay", 1, "1", AccountAllocationCategory.KIRO),
            tier("kiro", 2, "0.6", AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(member), List.of(mapping(member, relay)), List.of(gate("CHAT", "KEYS", "key-1"))));
        add(kiro("kiro-1"), relay);
        // Kiro was asked and did not answer in time: it may have generated, and been paid for it.
        kiroAnswers.put("kiro-1", () -> Mono.error(new java.util.concurrent.TimeoutException("no answer")));
        relayAnswers.put("relay-1", Mono::never);

        Disposable request = facade().chat(chatBody(), exchange(CHAT_PATH, null)).subscribe();
        assertThat(dispatched).containsExactly("kiro-1", "relay-1");
        request.dispose();

        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), eq("Client disconnected after dispatch"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aRelayServesACallerWithoutAKeyWhoHasNoReservationToHoldOrRelease() {
        // A pair that is verified and a gate that is on for every key: the callers without one are served like the others.
        configure(owner(relayMember("relay-1"), gate("CHAT", "ON")));
        add(relay, kiro("kiro-1"));
        ApiKeyPrincipal anonymous = new ApiKeyPrincipal("anonymous", "whale-assistant", "ADMIN", "whale-assistant", null,
            BigDecimal.ZERO, BigDecimal.ZERO, "*");
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(anonymous));
        when(rateLimiter.check("anonymous")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        ProxyFacade facade = facade(verified());

        ResponseEntity<?> response = facade.chat(chatBody(), exchange(CHAT_PATH, null)).block();

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        // Without a reservation there is nothing to note, release or settle for good.
        relayAnswers.put("relay-1", Mono::never);
        Disposable request = facade.chat(chatBody(), exchange(CHAT_PATH, null)).subscribe();
        request.dispose();
        verify(apiKeyService, never()).reserveUsage(any(), anyString(), any(), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void aCallerWithoutAKeyIsNotOnTheListOfAGateEvenWhereOneNamesIt() {
        // What an earlier release may have stored: the gate of a trial pair lists the id the callers without a key go by.
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "anonymous")));
        add(relay, kiro("kiro-1"));
        ApiKeyPrincipal anonymous = new ApiKeyPrincipal("anonymous", "whale-assistant", "ADMIN", "whale-assistant", null,
            BigDecimal.ZERO, BigDecimal.ZERO, "*");
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(anonymous));
        when(rateLimiter.check("anonymous")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));

        ResponseEntity<?> response = chat(chatBody());

        // Served at the operator's cost, there being no reservation: not by a relay that is on trial for the listed keys.
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verifyNoInteractions(xpDispatcher);
        assertThat(dispatched).containsExactly("kiro-1");
    }

    // ---- what the node remembers of a relay that failed (design 7.8) ----

    @Test
    void aPromptARelayRefusedForItsLengthGoesStraightToKiroTheNextTimeAndAShortOneStillGoesToTheRelay() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "This model's maximum context length is 8192 tokens"), null, "relay-1", "sk-secret"));

        // A compaction request: the whole history, longer than the relay takes although the window written for it says it does.
        ResponseEntity<?> compaction = chat(conversationOf(4_000));
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(compaction.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");

        // The same prompt, and a longer one, are not asked of the relay a second time, in the session or in another one.
        dispatched.clear();
        assertThat(chat(conversationOf(4_000)).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(chat(conversationOf(5_000)).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        ObjectNode anotherSession = conversationOf(4_500);
        anotherSession.withArray("messages").get(0).asObject().put("content", "another conversation");
        assertThat(chat(anotherSession).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "kiro-1");
        verify(xpDispatcher, times(1)).dispatch(any(), any(), any(), any(), anyBoolean());

        // A short request of the session is the relay's again, and it is billed as the relay's tier.
        relayAnswers.clear();
        dispatched.clear();
        ResponseEntity<?> shortOne = chat(chatBody());
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(shortOne.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(shortOne.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("1");
    }

    @Test
    void aPromptTheRelayRefusedForItsLengthPushesNoSessionDownToKiro() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));
        String binding = TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5",
            TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", chatBody()));

        // Once by the relay's own answer, then by the plan that leaves the relay out: neither moves the session.
        chat(conversationOf(4_000));
        assertThat(bindings.find(binding)).isEmpty();
        chat(conversationOf(4_000));
        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "kiro-1");
        assertThat(bindings.find(binding)).isEmpty();

        relayAnswers.clear();
        dispatched.clear();
        chat(chatBody());
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void whatTheRelayRefusedIsLearnedOnlyFromARefusalForLengthAndNotFromARequestItCouldNotTake() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400, error("invalid_request_error", "Unsupported parameter: x"),
            null, "relay-1", "sk-secret"));

        chat(conversationOf(4_000));
        dispatched.clear();
        chat(conversationOf(4_000));

        // The relay is asked again: it refused the conversion, which says nothing of how long a prompt it takes.
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
    }

    @Test
    void aWindowTheRelayShowedIsForgottenAfterSixHours() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));
        chat(conversationOf(4_000));
        dispatched.clear();

        clock.addAndGet(Duration.ofHours(6).minusSeconds(1).toNanos());
        chat(conversationOf(4_000));
        assertThat(dispatched).containsExactly("kiro-1");

        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        chat(conversationOf(4_000));
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
    }

    @Test
    void theRetryOfAnAnswerARelayBrokeOffGoesToTheNextRelayAndNotToTheOneThatMayStillBeGenerating() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> chat(chatBody()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(dispatched).containsExactly("relay-1");

        // The client's SDK retries the 502. Nothing else of the cache has changed, and the first relay would be the first
        // choice of the tier again.
        dispatched.clear();
        ResponseEntity<?> retry = chat(chatBody());
        assertThat(dispatched).containsExactly("relay-2");
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");

        // Another conversation of the key has no reason to keep from the relay.
        dispatched.clear();
        relayAnswers.clear();
        ObjectNode other = chatBody();
        other.withArray("messages").get(0).asObject().put("content", "another conversation");
        chat(other);
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void aRelayThatBrokeOffIsNotAskedAgainForTwoMinutesAndKiroServingInItsPlaceMovesNoSession() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));
        String binding = TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5",
            TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", chatBody()));

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        dispatched.clear();

        // The retry is answered by Kiro; the relay is not asked, though nothing is wrong with it any more.
        ResponseEntity<?> retry = chat(chatBody());
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verify(xpDispatcher, times(1)).dispatch(any(), any(), any(), any(), anyBoolean());
        // Kiro served it for the relay's sake and not for the tier's being out of room: the session is not bound to it.
        assertThat(bindings.find(binding)).isEmpty();
        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(100).toNanos());
        assertThat(chat(chatBody()).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");

        // Two minutes after the last interruption the relay is the session's again, at the first tier.
        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(121).toNanos());
        ResponseEntity<?> later = chat(chatBody());
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(later.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(later.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("1");
    }

    @Test
    void aMessagesRequestThatARelayBrokeOffForTheSessionIsRetriedElsewhereToo() {
        configure(owner(relayMember("relay-1"), gate("MESSAGES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> facade().messages(messagesBody(), exchange(MESSAGES_PATH, "session-1")).block())
            .isInstanceOf(ApiException.class);
        relayAnswers.clear();
        dispatched.clear();

        ResponseEntity<?> retry = facade().messages(messagesBody(), exchange(MESSAGES_PATH, "session-1")).block();
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");

        // The session is told by its own name, not by what it starts with: another one is served by the relay.
        dispatched.clear();
        facade().messages(messagesBody(), exchange(MESSAGES_PATH, "session-2")).block();
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void aFailureOfANativeAccountTeachesNothingAboutTheRelays() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        fails(kiroAnswers, "kiro-1", new UpstreamException(400, "Input is too long", false));

        // The relay is busy, and Kiro cannot take the prompt either.
        assertThatThrownBy(() -> chat(conversationOf(4_000))).isInstanceOf(ApiException.class);
        assertThat(dispatched).containsExactly("kiro-1");

        // What Kiro said of the length is nothing to the relay, whose plan is made as before once it is not busy.
        busy.clear();
        kiroAnswers.clear();
        dispatched.clear();
        ResponseEntity<?> response = chat(conversationOf(4_000));
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    // ---- the accounts that are out of service ----

    @Test
    void theRetestOfARoundThatFoundNothingAlsoTestsTheRelayThatIsOutOfService() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        // The relay is disabled and so is in no cache; Kiro is there and busy, so the round finds nothing.
        add(kiro("kiro-1"));
        busy.add("kiro-1");
        ProxyAccountEntity disabledRelay = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1");
        disabledRelay.setEnabled(false);

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(ApiException.class);

        Predicate<ProxyAccountEntity> filter = retestFilter();
        // It cannot serve this request, whose plan was made without it, but the tier never comes back unless it is tested.
        assertThat(filter.test(disabledRelay)).isTrue();
        assertThat(filter.test(kiro("kiro-1"))).isTrue();
        // An OpenAI-compatible key that is no relay of the vendor is none of the request's concern.
        assertThat(filter.test(account("glm-1", "OpenAI-compatible", "api-key", "https://glm.example/v1"))).isFalse();
        verify(xpDispatcher, never()).dispatch(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aRelayWhosePairIsNotOpenToTheKeyIsNotTestedForIt() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "another-key")));
        add(kiro("kiro-1"));
        busy.add("kiro-1");

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(ApiException.class);

        assertThat(retestFilter().test(account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1"))).isFalse();
    }

    @Test
    void noRelayIsTestedForARequestThatLeftTheRelaysBehind() {
        // The second tier holds a relay as well as Kiro, which is busy: after the first relay refused the request, the
        // round that finds nothing is not one for the relays of the tier it crossed to.
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-2", "kiro")), gate("CHAT", "KEYS", "key-1")));
        add(relay, secondRelay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));
        busy.add("kiro-1");

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(ApiException.class);

        assertThat(dispatched).containsExactly("relay-1");
        Predicate<ProxyAccountEntity> filter = retestFilter();
        assertThat(filter.test(secondRelay)).isFalse();
        assertThat(filter.test(kiro("kiro-1"))).isTrue();
    }

    @Test
    void aRelayThatIsOutOfServiceIsNotTestedEitherForARequestThatLeftTheRelaysBehind() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-3")), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(400,
            error("context_length_exceeded", "maximum context length"), null, "relay-1", "sk-secret"));
        busy.add("kiro-1");

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(ApiException.class);

        // The third relay is disabled, so the plan was made without it. It would be tested for a request that is still
        // looking for a relay, and is not for one that went on to the native accounts.
        assertThat(retestFilter().test(thirdRelay)).isFalse();
    }

    @Test
    void aRelayThatIsOutOfServiceIsTestedForARequestThatStillLooksForOne() {
        configure(owner(List.of(relayMember("relay-1"), relayMember("relay-3")), gate("CHAT", "KEYS", "key-1")));
        add(kiro("kiro-1"));
        busy.add("kiro-1");

        assertThatThrownBy(() -> chat(chatBody())).isInstanceOf(ApiException.class);

        assertThat(retestFilter().test(thirdRelay)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Predicate<ProxyAccountEntity> retestFilter() {
        ArgumentCaptor<Predicate<ProxyAccountEntity>> filter = ArgumentCaptor.forClass(Predicate.class);
        verify(scheduledTests).runWhenNoAvailableAccounts(filter.capture());
        return filter.getValue();
    }

    // ---- time ----

    @Test
    void aRelayIsGivenNoMoreTimeThanTheLeaseOfTheReservationCanStillCoverLessAMinute() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class)))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(300))));

        chat(chatBody());

        assertThat(lastTimeLimit).isEqualTo(Duration.ofSeconds(240));
    }

    @Test
    void aRelayIsNotAskedWhenTheLeaseLeavesLessThanHalfAMinuteAndKiroAnswersInstead() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        // A lease whose window fits what is left of the cap, which a real one does not at this point of a request: the
        // dispatches before would have been refused for it. The check is made all the same, and a relay not asked costs nothing.
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class)))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(10), Duration.ofSeconds(85))));

        ResponseEntity<?> response = chat(chatBody());

        verify(xpDispatcher, never()).dispatch(any(), any(), any(), any(), anyBoolean());
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
    }

    @Test
    void theTimeAMemberMayTakeIsBoundedByTheLease() {
        Member member = relayMember("relay-1");
        ApiKeyService.DispatchLease far = new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59));
        assertThat(ProxyFacade.relayTimeLimit(member, Optional.empty())).isEqualTo(Duration.ofSeconds(540));
        assertThat(ProxyFacade.relayTimeLimit(member, Optional.of(far))).isEqualTo(Duration.ofSeconds(540));
        assertThat(ProxyFacade.relayTimeLimit(member,
            Optional.of(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(100))))).isEqualTo(Duration.ofSeconds(40));
        // Under half a minute is too little to be worth asking.
        assertThat(ProxyFacade.relayTimeLimit(member,
            Optional.of(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(89))))).isNull();
        assertThat(ProxyFacade.relayTimeLimit(member,
            Optional.of(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ZERO)))).isNull();
    }

    @Test
    void aRelayBoundsItselfSoItsLeaseIsNotRenewedWhileItRuns() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        when(apiKeyService.renewDispatchLease(any(), anyString())).thenReturn(Mono.just(true));
        // The dispatcher's own time limit ends it, long before the lease: nothing renews the lease on the way.
        relayAnswers.put("relay-1", () -> Mono.delay(Duration.ofSeconds(500)).thenReturn(RELAY_ANSWER));
        ProxyFacade facade = facade();

        StepVerifier.withVirtualTime(() -> facade.chat(chatBody(), exchange(CHAT_PATH, null)))
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(499))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        verify(apiKeyService, never()).renewDispatchLease(any(), anyString());
    }

    // ---- a request that named the relay's tier ----

    @Test
    void aRequestPinnedToTheRelayIsServedByItAndNeverByKiro() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode body = chatBody();
        body.put("model", "claude-sonnet-4.5@relay");

        ResponseEntity<?> response = chat(body);

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("pinned");
        assertThat(dispatched).containsExactly("relay-1");
        // The name the client wrote is not what the relay or the log hears.
        assertThat(lastDispatch.wireModel()).isEqualTo("relay-sonnet");
        assertThat(onlyLog().model()).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void aRequestPinnedToTheRelayIsAnsweredWithTheRelaysFailureWhenItCannotServe() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "x"), null, "relay-1", "sk-secret"));
        ObjectNode body = chatBody();
        body.put("model", "claude-sonnet-4.5@relay");

        assertThatThrownBy(() -> chat(body))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // No other tier may serve it, at another price.
        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    // ---- nothing changes where there is no relay ----

    @Test
    void aVendorWithoutTiersNeverMeetsTheDispatcherOfRelays() {
        configure(SourceTierSnapshot.EMPTY);
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(kiro("kiro-1")));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(xpDispatcher);
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aMessagesRequestOfAVendorWithoutTiersNeverMeetsTheDispatcherEither() {
        configure(SourceTierSnapshot.EMPTY);
        when(accountPool.selectAnthropicAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "none")));
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(kiro("kiro-1")));

        ResponseEntity<?> response = facade().messages(messagesBody(), exchange(MESSAGES_PATH, null)).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(xpDispatcher);
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void tiersWithoutRelaysNeverMeetTheDispatcherOfRelaysEither() {
        configure(SourceTierSnapshot.of(7L, List.of(vendor()), List.of(tier("kiro", 1, "0.6", AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
        add(kiro("kiro-1"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verifyNoInteractions(xpDispatcher);
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
        StatsService.RequestLog row = onlyLog();
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
    }

    // The relay of one vendor is nothing to the requests of the others, though it makes the requests of every vendor be read: the
    // entries of the facade, the planner and the billing meet it only for the vendor it is registered in (invariant I4).

    /** The anthropic relay of the reference scenario, open to key-1 on both entries, beside the openai vendor with or without tiers. */
    private SourceTierSnapshot relayBesideOpenAi(boolean openAiHasTiers) {
        Member member = relayMember("relay-1");
        List<VendorConfig> vendors = new ArrayList<>(List.of(vendor()));
        List<Tier> tiers = new ArrayList<>(List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)));
        if (openAiHasTiers) {
            vendors.add(new VendorConfig("openai", true, false, false, true, 16, true, 7L));
            tiers.add(new Tier("openai", "official", 1, "official", new BigDecimal("0.5"),
                EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null));
        }
        return SourceTierSnapshot.of(7L, vendors, tiers, List.of(member), List.of(mapping(member, relay)),
            List.of(gate("CHAT", "KEYS", "key-1"), gate("MESSAGES", "KEYS", "key-1")));
    }

    private ObjectNode gptChatBody() {
        ObjectNode body = chatBody();
        body.put("model", "gpt-5.5");
        return body;
    }

    private ObjectNode gptMessagesBody() {
        ObjectNode body = messagesBody();
        body.put("model", "gpt-5.5");
        return body;
    }

    private ResponseEntity<?> messages(ObjectNode body) {
        return facade().messages(body, exchange(MESSAGES_PATH, null)).block();
    }

    /**
     * The relay of the anthropic vendor is open to this key, so the request was read for relays like any other of the entry
     * (that is the code these tests are about), and the plan of the request found none for it.
     */
    private void assertRelaysWereConsideredForTheRequestOnly() {
        assertThat(planned).hasSize(1);
        assertThat(planned.getFirst().entryFacts()).isNotNull();
    }

    /** What the request of a vendor without tiers is as it was before tiers existed, whatever relay another vendor has. */
    private void assertServedAsBeforeTiers(ResponseEntity<?> response, String accountId) {
        assertRelaysWereConsideredForTheRequestOnly();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dispatched).containsExactly(accountId);
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        verifyNoInteractions(xpDispatcher);
        // The dispatch mark and the settlement of an untiered request, and a reservation that carries no plan.
        verify(apiKeyService).markUsageDispatched(any(), anyString());
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString());
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
        ArgumentCaptor<String> pricing = ArgumentCaptor.forClass(String.class);
        verify(apiKeyService).reserveUsage(any(), anyString(), any(), pricing.capture());
        assertThat(pricing.getValue()).doesNotContain("tierPlan");
        verify(apiKeyService).accountUsage(any(), anyString(), any(), anyLong(), anyLong());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        StatsService.RequestLog row = onlyLog();
        assertThat(row.sourceTierRef()).isNull();
        assertThat(row.sourceTierLevel()).isNull();
        assertThat(row.sourceTierName()).isNull();
        assertThat(row.priceFactor()).isNull();
        assertThat(row.tierSelection()).isNull();
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
        assertThat(row.usageSource()).isNull();
        assertThat(row.stopReason()).isNull();
        assertThat(row.degradedFeatures()).isNull();
    }

    /** What the request of a tiered vendor with native accounts only is, whatever relay another vendor has. */
    private void assertServedByANativeTier(ResponseEntity<?> response, String accountId, String tierRef, String tierId) {
        assertRelaysWereConsideredForTheRequestOnly();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dispatched).containsExactly(accountId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("1");
        verifyNoInteractions(xpDispatcher);
        verify(apiKeyService).markUsageDispatchedTiered(any(), anyString(), eq(tierRef));
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
        verify(apiKeyService, never()).markUsageDispatched(any(), anyString());
        verify(apiKeyService).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), eq(tierRef), eq(1), any());
        StatsService.RequestLog row = onlyLog();
        assertThat(row.sourceTierRef()).isEqualTo(tierRef);
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
        assertThat(row.usageSource()).isNull();
        assertThat(row.degradedFeatures()).isNull();
    }

    @Test
    void aGptChatRequestOfAVendorWithoutTiersNeverMeetsTheRelayOfAnotherVendor() {
        configure(relayBesideOpenAi(false));
        add(relay, kiro("kiro-1"));
        when(accountPool.selectOpenAiApiKeyAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(account("openai-1", "OpenAI", "api-key", null)));

        ResponseEntity<?> response = chat(gptChatBody());

        assertServedAsBeforeTiers(response, "openai-1");
    }

    @Test
    void aGptMessagesRequestOfAVendorWithoutTiersNeverMeetsTheRelayOfAnotherVendor() {
        configure(relayBesideOpenAi(false));
        add(relay, kiro("kiro-1"));
        when(accountPool.selectAnthropicAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(account("anthropic-1", "Anthropic", "api-key", null)));

        ResponseEntity<?> response = messages(gptMessagesBody());

        assertServedAsBeforeTiers(response, "anthropic-1");
    }

    @Test
    void aGptChatRequestOfAVendorWhoseTiersHaveNoRelayNeverMeetsTheRelayOfAnotherVendor() {
        configure(relayBesideOpenAi(true));
        add(relay, kiro("kiro-1"), account("openai-1", "OpenAI", "api-key", null));

        ResponseEntity<?> response = chat(gptChatBody());

        assertServedByANativeTier(response, "openai-1", "openai:official", "official");
    }

    @Test
    void aGptMessagesRequestOfAVendorWhoseTiersHaveNoRelayNeverMeetsTheRelayOfAnotherVendor() {
        configure(relayBesideOpenAi(true));
        // The Messages endpoint is on the Anthropic route, which Kiro accounts serve too: the lowest tier takes them.
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = messages(gptMessagesBody());

        assertServedByANativeTier(response, "kiro-1", "openai:official", "official");
    }

    // The entries that are never read for relays, the Responses entry of Codex and the Gemini one, stay what they were whatever
    // relay the vendor of the model has and however open its gate is, as long as the registry offers no pair for them: they pass
    // no facts to the planner (design 3, step 5b). The Responses entry with a pair is ProxyFacadeTieredXpResponsesTest's.

    private static final String RESPONSES_PATH = "/v1/responses";
    private static final String GEMINI_PATH = "/v1beta/models/claude-sonnet-4.5:generateContent";

    /** The openai vendor with a relay as its first tier, open to key-1 on the Chat entry, and official keys as its second at half the price. */
    private SourceTierSnapshot openAiRelayBesideOfficialKeys() {
        return openAiRelayBesideOfficialKeys(false, true);
    }

    /**
     * The same, with the tier of the relay kept and the relay left out when {@code withRelay} is false, and with the names of the tiers
     * given to the users who cannot pay for the reservation when {@code publicListing} is true.
     */
    private SourceTierSnapshot openAiRelayBesideOfficialKeys(boolean publicListing, boolean withRelay) {
        Member member = new Member("openai", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
        MemberModel unverified = new MemberModel("openai", "relay-1", "gpt-5.4", "relay-gpt", 400_000, 8192, true, null, null);
        MemberModel verified = new MemberModel("openai", "relay-1", "gpt-5.4", "relay-gpt", 400_000, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, relay));
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig("openai", true, false, publicListing, true, 16, true, 7L)),
            List.of(new Tier("openai", "relay", 1, "relay", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true, true, null),
                new Tier("openai", "official", 2, "official", new BigDecimal("0.5"),
                    EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null)),
            withRelay ? List.of(member) : List.of(), withRelay ? List.of(verified) : List.of(),
            withRelay ? List.of(new PairGate("openai", "OPENAI_CHAT", "CHAT", "KEYS", List.of("key-1"))) : List.of());
    }

    private static PairRegistry offeringOpenAi() {
        return new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "openai", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.EXPERIMENTAL)));
    }

    private ObjectNode codexResponsesBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5.4").put("input", "list the files");
        return body;
    }

    private ObjectNode geminiBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents").addObject().put("role", "user").putArray("parts").addObject().put("text", "hi");
        body.putObject("generationConfig").put("maxOutputTokens", 4096);
        return body;
    }

    private static MockServerWebExchange namingTheRelayTier(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).header(TierSelectorParser.REQUEST_HEADER, "relay"));
    }

    /**
     * What a request of an entry that is not read for relays is, though the tiers of its vendor hold a relay with an open gate:
     * it is planned without the facts a relay is judged by, never meets the dispatcher of relays, is marked, settled and
     * logged as a request of native tiers, and moves no session.
     */
    private void assertServedByANativeTierAndNeverReadForRelays(
        ResponseEntity<?> response,
        String accountId,
        String tierRef,
        String tierId,
        String level
    ) {
        assertThat(planned).hasSize(1);
        assertThat(planned.getFirst().entryFacts()).isNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dispatched).containsExactly(accountId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo(level);
        verifyNoInteractions(xpDispatcher);
        verify(apiKeyService).markUsageDispatchedTiered(any(), anyString(), eq(tierRef));
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
        verify(apiKeyService, never()).markUsageDispatched(any(), anyString());
        verify(apiKeyService).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), eq(tierRef), eq(Integer.parseInt(level)), any());
        StatsService.RequestLog row = onlyLog();
        assertThat(row.sourceTierRef()).isEqualTo(tierRef);
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
        assertThat(row.usageSource()).isNull();
        assertThat(row.stopReason()).isNull();
        assertThat(row.degradedFeatures()).isNull();
        // The tier that serves is the only one there is for the entry: no session is pushed down, and none climbs.
        verify(bindings, never()).downgrade(any(), any(), any());
        verify(bindings, never()).climb(any(), any());
    }

    @Test
    void aCodexResponsesRequestIsServedByTheNativeTiersAsBeforeWhateverRelayTheVendorHasAndHoweverOpenItsGateIs() {
        configure(openAiRelayBesideOfficialKeys());
        add(relay, account("openai-1", "OpenAI", "api-key", null));
        when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> {
            dispatched.add(invocation.<ProxyAccountEntity>getArgument(0).getAccountId());
            return Mono.just(OPENAI_ANSWER);
        });

        ResponseEntity<?> response = facade(offeringOpenAi()).responses(codexResponsesBody(), exchange(RESPONSES_PATH, null)).block();

        assertServedByANativeTierAndNeverReadForRelays(response, "openai-1", "openai:official", "official", "2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        // The relay is the Chat entry's: the same configuration gives it the gpt request that OpenCode sends.
        dispatched.clear();
        ObjectNode opencode = gptChatBody();
        opencode.put("model", "gpt-5.4");
        ResponseEntity<?> chat = facade(offeringOpenAi()).chat(opencode, exchange(CHAT_PATH, null)).block();
        assertThat(chat.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(dispatched).containsExactly("relay-1");
    }

    /** OpenCode sends a verbosity of low with every request of a gpt model: the relay is given the request all the same. */
    @Test
    void aGptChatRequestThatAsksForALowVerbosityIsServedByTheRelayWhichIsNotSentItAndTheRequestLogsItAsLeftOut() {
        configure(openAiRelayBesideOfficialKeys());
        add(relay, account("openai-1", "OpenAI", "api-key", null));
        ObjectNode opencode = gptChatBody();
        opencode.put("model", "gpt-5.4").put("verbosity", "low");

        ResponseEntity<?> response = facade(offeringOpenAi()).chat(opencode, exchange(CHAT_PATH, null)).block();

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(lastDispatch.wireBody().has("verbosity")).isFalse();
        assertThat(lastDispatch.degraded()).contains("verbosity");
        assertThat(planned.getFirst().entryFacts().detected().features().stream().map(feature -> feature.label()))
            .contains("verbosity");
    }

    @Test
    void aGptChatRequestThatAsksForLogitBiasIsStillServedByTheOfficialKeysAndNotByTheRelay() {
        configure(openAiRelayBesideOfficialKeys());
        add(relay, account("openai-1", "OpenAI", "api-key", null));
        ObjectNode opencode = gptChatBody();
        opencode.put("model", "gpt-5.4").put("verbosity", "low").putObject("logit_bias").put("50256", -100);

        ResponseEntity<?> response = facade(offeringOpenAi()).chat(opencode, exchange(CHAT_PATH, null)).block();

        // The odds of the words are changed by the account that is handed them, and a relay is not.
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        assertThat(dispatched).containsExactly("openai-1");
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aCodexResponsesRequestThatNamedTheRelayTierByHeaderIsServedAutomaticallyAsBefore() {
        configure(openAiRelayBesideOfficialKeys());
        add(relay, account("openai-1", "OpenAI", "api-key", null));
        when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> {
            dispatched.add(invocation.<ProxyAccountEntity>getArgument(0).getAccountId());
            return Mono.just(OPENAI_ANSWER);
        });

        // No relay takes part in this entry, so the tier the header names has nothing for it: the hint is dropped.
        ResponseEntity<?> response = facade(offeringOpenAi()).responses(codexResponsesBody(), namingTheRelayTier(RESPONSES_PATH)).block();

        assertServedByANativeTierAndNeverReadForRelays(response, "openai-1", "openai:official", "official", "2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
    }

    /** The relay of the anthropic vendor with its gates on the Chat and Messages entries open to key-1, and Kiro beside it. */
    private void configureTheRelayOpenToTheKeyBesideKiro() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1"), gate("MESSAGES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
    }

    /** The gates really are open to this key: the entries the relay is offered on give it a request of the key. */
    private void assertTheGatesAreOpenToTheKey() {
        dispatched.clear();
        assertThat(chat(chatBody()).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(messages(messagesBody()).getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(dispatched).containsExactly("relay-1", "relay-1");
    }

    @Test
    void aGeminiRequestIsServedByTheNativeTiersAsBeforeWhateverRelayTheVendorHasWhileTheGateOfItsOwnEntryIsShut() {
        configureTheRelayOpenToTheKeyBesideKiro();

        ResponseEntity<?> response = facadeWithTheGeminiConverter()
            .geminiGenerateContent("claude-sonnet-4.5", geminiBody(), exchange(GEMINI_PATH, null)).block();

        assertServedByANativeTierAndNeverReadForRelays(response, "kiro-1", "anthropic:kiro", "kiro", "2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        assertTheGatesAreOpenToTheKey();
    }

    @Test
    void aGeminiRequestThatNamedTheRelayTierByHeaderIsServedAutomaticallyAsBeforeWhileTheGateOfItsOwnEntryIsShut() {
        configureTheRelayOpenToTheKeyBesideKiro();

        ResponseEntity<?> response = facadeWithTheGeminiConverter()
            .geminiGenerateContent("claude-sonnet-4.5", geminiBody(), namingTheRelayTier(GEMINI_PATH)).block();

        assertServedByANativeTierAndNeverReadForRelays(response, "kiro-1", "anthropic:kiro", "kiro", "2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
        assertTheGatesAreOpenToTheKey();
    }

    // The entries no relay takes part in, and the requests a relay is not given, reserve what they always did: a tier of relays
    // alone that nobody is asked of is not reserved for by what its relays would have been sent (design 5.2).

    /** What a request reserves, whatever it ends in. */
    private BigDecimal reservedBy(Supplier<Mono<ResponseEntity<?>>> request) {
        try {
            request.get().block();
        } catch (ApiException failed) {
            // Nothing is cached to serve it, or the balance does not cover it: the reservation is what is looked at.
        }
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).reserveUsage(any(), anyString(), credits.capture(), anyString());
        clearInvocations(apiKeyService);
        return credits.getValue();
    }

    private ObjectNode longCodexResponsesBody() {
        ObjectNode body = codexResponsesBody();
        body.put("input", "word ".repeat(20_000));
        return body;
    }

    private String lowBalanceAdvice(Supplier<Mono<ResponseEntity<?>>> request) {
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.error(
            new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "balance too low")));
        try {
            request.get().block();
        } catch (ApiException refused) {
            assertThat(refused.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
            return refused.getMessage();
        } finally {
            when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
            clearInvocations(apiKeyService);
        }
        throw new AssertionError("The request was not refused");
    }

    @Test
    void aCodexResponsesRequestReservesWhatItAlwaysDidWhateverRelayTierSitsAboveTheNativeOnesAndNothingNativeIsCached() {
        ObjectNode request = longCodexResponsesBody();
        Supplier<Mono<ResponseEntity<?>>> codex = () -> facade(offeringOpenAi()).responses(request.deepCopy(), exchange(RESPONSES_PATH, null));
        configure(openAiRelayBesideOfficialKeys(false, false));
        BigDecimal withoutRelay = reservedBy(codex);

        configure(openAiRelayBesideOfficialKeys(false, true));
        add(relay);
        BigDecimal withRelay = reservedBy(codex);

        // The entry is not read for relays, so the tier of relays alone is reserved for as any tier that nobody is asked of.
        assertThat(withRelay).isEqualByComparingTo(withoutRelay);
        assertThat(withRelay).isGreaterThan(QUOTE.estimate(0, RequestBounds.outputTokens(request)));
    }

    @Test
    void aCodexResponsesRequestThatCannotPayIsNotAdvisedToNameTheRelayTierItsReservationDoesNotCount() {
        Supplier<Mono<ResponseEntity<?>>> codex = () -> facade(offeringOpenAi())
            .responses(longCodexResponsesBody(), exchange(RESPONSES_PATH, null));
        configure(openAiRelayBesideOfficialKeys(true, false));
        String without = lowBalanceAdvice(codex);

        configure(openAiRelayBesideOfficialKeys(true, true));
        add(relay);
        String with = lowBalanceAdvice(codex);

        // The relay is no tier this request can be pinned to: the advice would be answered with SOURCE_TIER_NOT_OFFERED.
        assertThat(with).isEqualTo(without);
        assertThat(with).contains("gpt-5.4@official").doesNotContain("@relay");
    }

    @Test
    void aGeminiRequestReservesWhatItAlwaysDidWhateverRelayTierSitsAboveKiroAndNoKiroAccountIsCached() {
        Supplier<Mono<ResponseEntity<?>>> gemini = () -> facade().geminiGenerateContent("claude-sonnet-4.5", geminiBody(),
            exchange(GEMINI_PATH, null));
        configure(anthropicTiersWithoutRelay());
        BigDecimal withoutRelay = reservedBy(gemini);

        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1"), gate("MESSAGES", "KEYS", "key-1")));
        add(relay);
        BigDecimal withRelay = reservedBy(gemini);

        assertThat(withRelay).isEqualByComparingTo(withoutRelay);
        assertThat(withRelay).isGreaterThan(QUOTE.estimate(0, 4096));
    }

    @Test
    void aRequestOfAnEntryWhoseGateIsNotOpenToTheKeyReservesWhatItAlwaysDidToo() {
        Supplier<Mono<ResponseEntity<?>>> chat = () -> facade().chat(chatBody(), exchange(CHAT_PATH, null));
        configure(anthropicTiersWithoutRelay());
        BigDecimal withoutRelay = reservedBy(chat);

        // A gate that is open to other keys alone, and an entry that has no gate: the key's request is not given to the relay.
        for (PairGate[] gates : List.of(new PairGate[] {gate("CHAT", "KEYS", "another-key")}, new PairGate[0])) {
            configure(owner(relayMember("relay-1"), gates));
            cached.clear();
            add(relay);
            assertThat(reservedBy(chat)).isEqualByComparingTo(withoutRelay);
        }
    }

    @Test
    void aRelayOfAPairThatIsNotOpenForTheKeyIsNeverAsked() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "another-key")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = chat(chatBody());

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verifyNoInteractions(xpDispatcher);
    }

    // ---- fixtures ----

    private ResponseEntity<?> chat(ObjectNode body) {
        return chat(body, null);
    }

    private ResponseEntity<?> chat(ObjectNode body, String session) {
        return facade().chat(body, exchange(CHAT_PATH, session)).block();
    }

    private ProxyFacade facade() {
        return facade(offering());
    }

    private ProxyFacade facade(PairRegistry pairs) {
        return facade(pairs, Duration.ZERO);
    }

    /** A facade that serves every tiered request as one that began {@code age} ago, as the deadline for crossing counts it. */
    private ProxyFacade facade(PairRegistry pairs, Duration age) {
        return facade(pairs, age, null);
    }

    /** As the facade is built by Spring, which gives the Gemini entry the converter its relays are given requests by. */
    private ProxyFacade facadeWithTheGeminiConverter() {
        return facade(offering(), Duration.ZERO, new GeminiChatConverter(objectMapper));
    }

    private ProxyFacade facade(PairRegistry pairs, Duration age, GeminiChatConverter geminiConverter) {
        DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog,
            accountPool, payments, pricing, bindings, true, true, pairs, new XpSkipCounts(), learnedLimits);
        TierRuntime.Planner recording = new TierRuntime.Planner() {
            @Override
            public Mono<TierPlan> plan(TierPlanRequest request) {
                return planner.plan(request).doOnNext(plan -> planned.add(request));
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
        return new ProxyFacade(
            apiKeyService,
            accountPool,
            mock(AccountAutoSuspendService.class),
            accountSettings,
            scheduledTests,
            modelCatalog,
            modelMapping,
            rateLimiter,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiroClient,
            openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            new OpenAiResponseStateStore(objectMapper),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            anthropicClient,
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            payments,
            pricing,
            statsService,
            admissionStore,
            ownerStore,
            new TierRuntime(recording, bindings, learnedLimits),
            xpDispatcher,
            null,
            geminiConverter
        ) {
            @Override
            Mono<ResponseEntity<?>> serveTieredChat(TieredRequest run, ResponseMode responseMode) {
                return super.serveTieredChat(began(run, age), responseMode);
            }
        };
    }

    private static ProxyFacade.TieredRequest began(ProxyFacade.TieredRequest run, Duration ago) {
        return new ProxyFacade.TieredRequest(run.plan(), run.principal(), run.requestId(), run.path(), run.requestedModel(),
            run.upstreamModel(), run.sessionHint(), run.stream(), run.route(), run.fastMode(), run.preferCodexAccounts(),
            run.preferDeepSeekAccounts(), run.routingPolicy(), run.upstreamRequest(), run.responseRequest(),
            run.start().minus(ago), run.requestMovable(), run.ownerMayBeLeft());
    }

    private static MockServerWebExchange exchange(String path, String session) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post(path);
        if (session != null) {
            request.header(SESSION_HEADER, session);
        }
        return MockServerWebExchange.from(request);
    }

    private List<AdmissionResult> reconciled() {
        ArgumentCaptor<AdmissionResult> results = ArgumentCaptor.forClass(AdmissionResult.class);
        verify(admissionStore, org.mockito.Mockito.atLeastOnce()).reconcile(results.capture());
        return results.getAllValues();
    }

    private List<StatsService.RequestLog> logs() {
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, org.mockito.Mockito.atLeastOnce()).log(logs.capture());
        return logs.getAllValues();
    }

    private StatsService.RequestLog onlyLog() {
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, times(1)).log(logs.capture());
        return logs.getValue();
    }

    private static void fails(Map<String, Supplier<Mono<PlatformCompletionResult>>> answers, String accountId, Throwable error) {
        answers.put(accountId, () -> Mono.error(error));
    }

    private void add(ProxyAccountEntity... accounts) {
        cached.addAll(List.of(accounts));
    }

    private void configure(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    /** The pairs this release is taken to offer: both entries of the anthropic vendor, on trial. */
    private static PairRegistry offering() {
        return new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.EXPERIMENTAL)));
    }

    /** The same pairs, verified: a gate that is on opens them to every key. */
    private static PairRegistry verified() {
        return new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.VERIFIED),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.VERIFIED)));
    }

    private static PairGate gate(String entry, String state, String... keys) {
        return new PairGate("anthropic", "OPENAI_CHAT", entry, state, List.of(keys));
    }

    private static VendorConfig vendor() {
        return new VendorConfig("anthropic", true, false, false, true, 16, true, 7L);
    }

    private static Tier tier(String tierId, int level, String factor, AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = categories.length == 0
            ? EnumSet.noneOf(AccountAllocationCategory.class)
            : EnumSet.copyOf(List.of(categories));
        return new Tier("anthropic", tierId, level, tierId, new BigDecimal(factor), listed, true, true, null);
    }

    /** The reference scenario: the relay as the first tier, Kiro as the second at 0.6. */
    private SourceTierSnapshot owner(Member member, PairGate... gates) {
        return owner(List.of(member), gates);
    }

    private SourceTierSnapshot owner(List<Member> members, PairGate... gates) {
        List<MemberModel> models = new ArrayList<>();
        for (Member member : members) {
            models.add(mapping(member, switch (member.accountId()) {
                case "relay-2" -> secondRelay;
                case "relay-3" -> thirdRelay;
                default -> relay;
            }));
        }
        return SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            members, models, List.of(gates));
    }

    /** The tiers of the reference scenario with no relay registered in the first: an empty tier above Kiro's. */
    private static SourceTierSnapshot anthropicTiersWithoutRelay() {
        return SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(), List.of(), List.of());
    }

    private static Member relayMember(String accountId) {
        return relayMember(accountId, "relay");
    }

    private static Member relayMember(String accountId, String tierId) {
        return new Member("anthropic", accountId, tierId, "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
    }

    /** A mapping of Claude Code's model, verified for the account as it is now. */
    private static MemberModel mapping(Member member, ProxyAccountEntity account) {
        MemberModel unverified = new MemberModel(member.vendor(), member.accountId(), "claude-sonnet-4.5", "relay-sonnet", 200_000, 8192,
            true, null, null);
        return new MemberModel(member.vendor(), member.accountId(), "claude-sonnet-4.5", "relay-sonnet", 200_000, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, account));
    }

    private ObjectNode chatBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    /** A conversation of the same session as {@link #chatBody()} that goes on for {@code words} words, as a compaction request does. */
    private ObjectNode conversationOf(int words) {
        ObjectNode body = chatBody();
        body.withArray("messages").addObject().put("role", "assistant").put("content", "ok");
        body.withArray("messages").addObject().put("role", "user").put("content", "word ".repeat(words));
        return body;
    }

    private ObjectNode messagesBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5").put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", "list the files");
        ObjectNode tool = body.putArray("tools").addObject();
        tool.put("name", "Bash").put("description", "Runs a command");
        tool.putObject("input_schema").put("type", "object").putObject("properties").putObject("command").put("type", "string");
        return body;
    }

    private String error(String code, String message) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", message).put("type", "invalid_request_error").put("code", code);
        return body.toString();
    }

    private static ProxyAccountEntity kiro(String accountId) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc", null);
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
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
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
