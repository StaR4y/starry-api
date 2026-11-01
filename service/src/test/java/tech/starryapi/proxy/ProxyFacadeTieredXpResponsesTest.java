package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
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
import tech.starryapi.platform.openai.ApplyPatchEmulation;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.CodexRequests;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayAnswerWithheldException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Limits;
import tech.starryapi.platform.xproto.ResponsesChatConverter;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Responses entry served by a relay, end to end through the facade and its dispatch loop (design 4.8, 7.5, 7.8, 7.9):
 * Codex asking for a Claude model, a relay as the first tier of the anthropic vendor and Kiro as the second, the way the
 * owner's example has it. The planner is the real one with a registry that offers the Responses pair; the dispatcher of
 * the relays is a stand-in that answers per account, and so are Kiro and the account pool. What is looked at is what the
 * relay is asked, what Codex is answered, who owns the chain the answer starts, what the next turns of a chain meet, and
 * that a request that no relay takes part in is served as it was.
 */
class ProxyFacadeTieredXpResponsesTest {

    private static final String RESPONSES_PATH = "/v1/responses";
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult KIRO_ANSWER = new PlatformCompletionResult("from kiro", USAGE);
    private static final PlatformCompletionResult OPENAI_ANSWER = new PlatformCompletionResult("from openai", USAGE);
    private static final PlatformCompletionResult RELAY_ANSWER = relayAnswer("from the relay", StopReason.END_TURN);
    /** $3/M in, $15/M out: 8,000 in and 1,000 out cost 0.039 at the full price and 0.0234 at 0.6. */
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));
    private static final String CALL_ID = "call_ABCDEFGHIJKLMNOPQRSTUVWX";
    /**
     * The limit a request that names none is held to (D14): the default of the gateway, which claude-sonnet-4.5 writes, the
     * catalog listing 64,000 for it.
     */
    private static final int DEFAULT_OUTPUT = 16_000;
    /** What a relay whose mapping states no maximum output is given for a request that names none (D15), as it was before D11. */
    private static final int RELAY_OUTPUT = 4_096;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    /** What the operator set the default output of a Responses request that names none to: the facades of this test are built with it. */
    private long defaultOutputTokens = ProxyProperties.DEFAULT_RESPONSES_OUTPUT_TOKENS;
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
    private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
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
    /** The dispatches that were made to relays, in order. */
    private final List<TierDispatch> relayDispatches = new ArrayList<>();
    /** What each relay refuses of the request it is shown, if it does: the error it answers with, or null where it serves. */
    private final Map<String, Function<TierDispatch, Throwable>> relayRefuses = new HashMap<>();
    /** What the Kiro accounts were asked, as the payload factory made it. */
    private final List<JsonNode> kiroPayloads = new ArrayList<>();
    /** The requests the planner made a plan for, as the entries handed them over. */
    private final List<TierPlanRequest> planned = new ArrayList<>();
    private final ProxyAccountEntity relay = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private Limits lastLimits;
    /** Whether the output limit of each dispatch to a relay was the gateway's own default, in order. */
    private final List<Boolean> defaultOutputLimits = new ArrayList<>();

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
        when(ownerStore.findOwner(anyString())).thenReturn(Mono.empty());
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
            kiroPayloads.add(invocation.getArgument(1));
            dispatched.add(account.getAccountId());
            return kiroAnswers.getOrDefault(account.getAccountId(), () -> Mono.just(KIRO_ANSWER)).get();
        });
        when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            dispatched.add(account.getAccountId());
            return Mono.just(OPENAI_ANSWER);
        });
        when(xpDispatcher.dispatch(any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            relayDispatches.add(invocation.getArgument(1));
            lastLimits = invocation.getArgument(2);
            defaultOutputLimits.add(invocation.getArgument(4));
            dispatched.add(account.getAccountId());
            TierDispatch dispatch = invocation.getArgument(1);
            Throwable refusal = relayRefuses.getOrDefault(account.getAccountId(), ignored -> null).apply(dispatch);
            if (refusal != null) {
                return Mono.error(refusal);
            }
            return relayAnswers.getOrDefault(account.getAccountId(), () -> Mono.just(RELAY_ANSWER)).get()
                .map(answer -> new PlatformCompletionResult(answer.content(), answer.reasoningContent(), answer.toolUses(), answer.usage(),
                    answer.stopReason(), answer.usageSource(), dispatch.degraded()));
        });
    }

    // ---- the relay serves Codex ----

    @Test
    void aCodexRequestForAClaudeModelIsServedByTheRelayAndAnsweredWithTheEventsOfAResponse() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = responses(codexTurn());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("1");
        assertThat(response.getHeaders().toString()).doesNotContain("relay.example", "relay-sonnet", "OPENAI_CHAT");
        assertThat(eventTypes(response)).startsWith("response.created").contains("response.output_text.delta").endsWith("response.completed", "[DONE]");
        JsonNode answer = finalResponse(response);
        assertThat(answer.path("status").asString()).isEqualTo("completed");
        // The catalog model is echoed, not the name the relay was asked for.
        assertThat(answer.path("model").asString()).isEqualTo("claude-sonnet-4.5");
        assertThat(answer.path("output").path(0).path("content").path(0).path("text").asString()).isEqualTo("from the relay");
        assertThat(dispatched).containsExactly("relay-1");
        verify(kiroClient, never()).complete(any(), any(), anyString());
    }

    @Test
    void aClientThatDoesNotStreamIsAnsweredWithTheResponseItself() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = responses(codexTurn().put("stream", false));

        JsonNode body = (JsonNode) response.getBody();
        assertThat(body.path("object").asString()).isEqualTo("response");
        assertThat(body.path("output_text").asString()).isEqualTo("from the relay");
        assertThat(body.path("id").asString()).startsWith("resp_");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    @Test
    void theRelayIsSentTheChatBodyOfTheRequestAsItIsReservedAndNothingOfTheRestOfWhatCodexSent() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn());

        TierDispatch dispatch = relayDispatches.getFirst();
        assertThat(dispatch.kind()).isEqualTo(TierDispatch.Kind.OPENAI_CHAT);
        assertThat(dispatch.pair().entry()).isEqualTo(Entry.RESPONSES);
        assertThat(dispatch.wireModel()).isEqualTo("relay-sonnet");
        JsonNode body = dispatch.wireBody();
        // The instructions and the developer message are system messages, the user's messages are the user's.
        assertThat(body.path("messages")).extracting(message -> message.path("role").asString())
            .containsExactly("system", "system", "user");
        assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo("You are Codex.");
        assertThat(body.path("messages").get(1).path("content").asString()).isEqualTo("<permissions instructions>");
        assertThat(body.path("messages").get(2).path("content").asString()).contains("<environment_context>", "run echo hello");
        assertThat(body.path("tools")).extracting(tool -> tool.path("function").path("name").asString())
            .containsExactly("exec_command", "update_plan");
        assertThat(body.path("tool_choice").asString()).isEqualTo("auto");
        // The request did not name a limit, and the mapping of the relay states no maximum output: the relay is given and held to
        // the 4,096 it always was, not the default output of the gateway that the reservation assumes (D15).
        assertThat(body.path("max_tokens").asInt()).isEqualTo(RELAY_OUTPUT);
        assertThat(lastLimits.maxOutputTokens()).isEqualTo(RELAY_OUTPUT);
        assertThat(body.path("stream").booleanValue()).isTrue();
        assertThat(body.toString()).doesNotContain("web_search", "multi_agent_v1", "client_metadata", "prompt_cache_key",
            "x-codex-installation-id", "previous_response_id");
        assertThat(body.has("include")).isFalse();
        assertThat(body.has("store")).isFalse();
        assertThat(dispatch.degraded()).containsExactlyInAnyOrder("builtin_tool", "prompt_cache_key");
    }

    @Test
    void theRequestLogSaysTheRelayServedItAndTheRelayIsChargedThePriceOfItsTier() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn());

        verify(apiKeyService).markUsageDispatchedTiered(any(), anyString(), eq("anthropic:relay"), eq(Duration.ofSeconds(540)));
        // 8,000 in and 1,000 out at the full price of the relay's tier, factor 1.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.039")) == 0), eq(8_000L), eq(1_000L), eq("anthropic:relay"), eq(1),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(BigDecimal.ONE) == 0));
        StatsService.RequestLog row = onlyLog();
        assertThat(row.success()).isTrue();
        assertThat(row.accountId()).isEqualTo("relay-1");
        assertThat(row.sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(row.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(row.wireModel()).isEqualTo("relay-sonnet");
        assertThat(row.usageSource()).isEqualTo("REPORTED");
        assertThat(row.stopReason()).isEqualTo("END_TURN");
        assertThat(row.degradedFeatures()).contains("builtin_tool");
        // What the row says of the model and the path is what it always said.
        assertThat(row.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(row.path()).isEqualTo(RESPONSES_PATH);
    }

    @Test
    void aLengthOfTheAnswerThatCodexAsksForIsLeftOutAndRecordedAndTheRelayStillServes() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode body = codexTurn();
        body.putObject("text").put("verbosity", "low");

        responses(body);

        // D6: the advice about the length is not sent and does not keep the relay from the request.
        assertThat(dispatched).containsExactly("relay-1");
        assertThat(onlyLog().degradedFeatures()).contains("verbosity");
        assertThat(relayDispatches.getFirst().wireBody().toString()).doesNotContain("verbosity");
    }

    // ---- what the relay answers with ----

    @Test
    void aToolCallOfTheRelayReachesCodexAsAFunctionCallUnderTheIdItWasGiven() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(execCall()));

        JsonNode answer = finalResponse(responses(codexTurn()));

        JsonNode call = answer.path("output").path(0);
        assertThat(call.path("type").asString()).isEqualTo("function_call");
        assertThat(call.path("name").asString()).isEqualTo("exec_command");
        assertThat(call.path("call_id").asString()).isEqualTo(CALL_ID);
        assertThat(call.path("id").asString()).startsWith("fc_");
        assertThat(call.path("arguments").asString()).isEqualTo("{\"cmd\":\"echo hello\"}");
    }

    @Test
    void anEditCallOfTheRelayReachesCodexAsACustomToolCallWithThePatchForItsInput() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        String patch = "*** Begin Patch\n*** Add File: a.txt\n+hello\n*** End Patch\n";
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult("", "", List.of(new PlatformToolUse(
            CALL_ID, "apply_patch", objectMapper.createObjectNode().put("patch", patch), "custom_tool_call", patch, null)),
            USAGE, StopReason.TOOL_USE, UsageSource.REPORTED, List.of())));

        JsonNode answer = finalResponse(responses(codexTurn().put("stream", false)));

        JsonNode call = answer.path("output").path(0);
        assertThat(call.path("type").asString()).isEqualTo("custom_tool_call");
        assertThat(call.path("input").asString()).isEqualTo(patch);
        assertThat(call.path("call_id").asString()).isEqualTo(CALL_ID);
    }

    @Test
    void anEditTheRelayAskedForComesBackToItAsTheCallOfAFunctionWithOnePatchArgumentWhenCodexAnswersIt() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        String patch = "*** Begin Patch\n*** Add File: a.txt\n+hello\n*** End Patch\n";
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult("", "", List.of(new PlatformToolUse(
            CALL_ID, "apply_patch", objectMapper.createObjectNode().put("patch", patch), "custom_tool_call", patch, null)),
            USAGE, StopReason.TOOL_USE, UsageSource.REPORTED, List.of())));
        ObjectNode first = codexTurn().put("stream", false);
        first.withArray("tools").addObject().put("type", "custom").put("name", "apply_patch").put("description", "Edits files");
        JsonNode answer = finalResponse(responses(first));
        JsonNode asked = answer.path("output").path(0);
        assertThat(asked.path("type").asString()).isEqualTo("custom_tool_call");
        // The relay was offered a function of that name, with the one argument the patch is given in.
        JsonNode offered = relayDispatches.getFirst().wireBody().path("tools");
        assertThat(offered).anySatisfy(tool -> {
            assertThat(tool.path("function").path("name").asString()).isEqualTo("apply_patch");
            assertThat(tool.path("function").path("parameters").path("required").get(0).asString()).isEqualTo("patch");
        });

        // Codex does the edit and says so as the output of a custom tool call, with the call as it had it.
        relayAnswers.put("relay-1", () -> Mono.just(RELAY_ANSWER));
        ObjectNode second = codexTurn().put("stream", false);
        second.set("tools", first.path("tools"));
        second.withArray("input").add(asked);
        second.withArray("input").addObject().put("type", "custom_tool_call_output").put("call_id", CALL_ID).put("output", "Done!");
        responses(second);

        JsonNode messages = relayDispatches.getLast().wireBody().path("messages");
        JsonNode call = messages.get(messages.size() - 2);
        assertThat(call.path("role").asString()).isEqualTo("assistant");
        assertThat(call.path("tool_calls").get(0).path("id").asString()).isEqualTo(CALL_ID);
        assertThat(call.path("tool_calls").get(0).path("function").path("name").asString()).isEqualTo("apply_patch");
        assertThat(objectMapper.readTree(call.path("tool_calls").get(0).path("function").path("arguments").asString()).path("patch").asString())
            .isEqualTo(patch);
        assertThat(messages.get(messages.size() - 1).path("role").asString()).isEqualTo("tool");
        assertThat(messages.get(messages.size() - 1).path("content").asString()).isEqualTo("Done!");
    }

    /**
     * Codex names no output limit, so the 4,096 tokens the relay was told it may write are the gateway's own bound. Codex takes
     * a response that is incomplete for a failed stream and sends the request again, five more times, and every answer of the
     * relay is paid for: the answer is a finished one, as the answer of every native account is.
     */
    @Test
    void anAnswerCutAtTheLimitTheGatewayReservedForARequestThatNamedNoneIsAFinishedResponseAndIsChargedForWhatItWrote() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(relayAnswer("cut off in the mi", StopReason.MAX_TOKENS)));

        ResponseEntity<?> streamed = responses(codexTurn());

        assertThat(eventTypes(streamed)).contains("response.completed").doesNotContain("response.incomplete");
        JsonNode answer = finalResponse(streamed);
        assertThat(answer.path("status").asString()).isEqualTo("completed");
        assertThat(answer.path("incomplete_details").isNull()).isTrue();
        assertThat(answer.path("output").path(0).path("content").path(0).path("text").asString()).isEqualTo("cut off in the mi");

        JsonNode whole = (JsonNode) responses(codexTurn().put("stream", false)).getBody();
        assertThat(whole.path("status").asString()).isEqualTo("completed");
        assertThat(whole.path("incomplete_details").isNull()).isTrue();
        // The relay was told the bound, and the request log and the charge are of what it wrote.
        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(RELAY_OUTPUT);
        verify(apiKeyService, times(2)).accountUsageServed(any(), anyString(), any(), eq(8_000L), eq(1_000L), eq("anthropic:relay"), eq(1), any());
        assertThat(logs().getFirst().stopReason()).isEqualTo("MAX_TOKENS");
    }

    @Test
    void anAnswerCutAtTheGatewaysLimitAfterAWholeCallIsAFinishedResponseThatHasTheCall() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult("I will run it", "", List.of(execToolCall()), USAGE,
            StopReason.MAX_TOKENS, UsageSource.REPORTED, List.of())));

        ResponseEntity<?> streamed = responses(codexTurn());

        // The call that came out whole is Codex's to run, once. An answer that lost a call to the bound never gets here: the
        // dispatcher made it an interruption (XpDispatcherTest), so what is rendered has all the calls the model made.
        assertThat(eventTypes(streamed)).endsWith("response.completed", "[DONE]").doesNotContain("response.incomplete");
        JsonNode answer = finalResponse(streamed);
        assertThat(answer.path("status").asString()).isEqualTo("completed");
        assertThat(answer.path("output")).extracting(item -> item.path("type").asString()).containsExactly("message", "function_call");
        assertThat(answer.path("output").path(1).path("call_id").asString()).isEqualTo(CALL_ID);
    }

    /** Only the client's own limit is one to report: it asked for it, and a call it is shown it may run once. */
    @Test
    void anAnswerThatReachedTheLimitTheClientNamedIsAnIncompleteResponseThatHasNoCall() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult("I will run it", "", List.of(execToolCall()), USAGE,
            StopReason.MAX_TOKENS, UsageSource.REPORTED, List.of())));

        ResponseEntity<?> streamed = responses(codexTurn().put("max_output_tokens", 2000));

        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(2000);
        assertThat(eventTypes(streamed)).endsWith("response.incomplete", "[DONE]").doesNotContain("response.completed")
            .noneMatch(type -> type.contains("function_call"));
        JsonNode answer = finalResponse(streamed);
        assertThat(answer.path("status").asString()).isEqualTo("incomplete");
        assertThat(answer.path("incomplete_details").path("reason").asString()).isEqualTo("max_output_tokens");
        assertThat(answer.path("output")).extracting(item -> item.path("type").asString()).containsExactly("message");
        assertThat(answer.path("output").path(0).path("content").path(0).path("text").asString()).isEqualTo("I will run it");
        // The relay wrote the call all the same, and the log and the charge are of the answer it gave.
        assertThat(logs().getFirst().stopReason()).isEqualTo("MAX_TOKENS");
        verify(apiKeyService).accountUsageServed(any(), anyString(), any(), eq(8_000L), eq(1_000L), eq("anthropic:relay"), eq(1), any());
    }

    @Test
    void aLimitNamedInAnyFieldOfTheRequestCountsAsTheClientsOwn() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(relayAnswer("cut off in the mi", StopReason.MAX_TOKENS)));

        for (String field : List.of("max_output_tokens", "max_completion_tokens", "max_tokens")) {
            JsonNode whole = (JsonNode) responses(codexTurn().put("stream", false).put(field, 1500)).getBody();

            assertThat(whole.path("status").asString()).as(field).isEqualTo("incomplete");
            assertThat(whole.path("incomplete_details").path("reason").asString()).as(field).isEqualTo("max_output_tokens");
        }
    }

    /**
     * The dispatcher judges what a bound that cut an answer off cost it by whose the bound was: it is told, and only a request
     * that named no limit is held to one the gateway chose.
     */
    @Test
    void theRelayIsToldWhetherTheOutputLimitItWasHeldToIsTheGatewaysOwn() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        responses(codexTurn().put("stream", false).put("max_output_tokens", 2000));
        responses(codexTurn().put("stream", false).put("max_tokens", 3000));

        assertThat(defaultOutputLimits).containsExactly(true, false, false);
    }

    /**
     * An answer the dispatcher held back (cut inside a call, or only reasoning, at the gateway's bound; stopped by the relay's
     * filter) ends the request like an interruption: Codex is told 502 and not that the response is incomplete, nothing is
     * charged, and its retry of the same request is not given to the relay again but to Kiro. It is no failure of the relay: its
     * account is not counted against and the admission store is told of an answer.
     */
    @Test
    void anAnswerTheGatewayHeldBackEndsTheRequestAndTheClientsRetryIsKirosWithNothingChargedForIt() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAnswerWithheldException("The source's answer was cut off before it could be used"));

        assertThatThrownBy(() -> responses(codexTurn(), "codex-session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
        assertThat(logs().getFirst().stopReason()).as("counted apart from the interruptions").isEqualTo("WITHHELD");

        // Codex sends the request again, up to five times; every one is Kiro's, and the relay is asked once.
        for (int retry = 0; retry < 5; retry++) {
            assertThat(finalResponse(responses(codexTurn().put("stream", false), "codex-session-1")).path("status").asString())
                .isEqualTo("completed");
        }
        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "kiro-1", "kiro-1", "kiro-1", "kiro-1");
        verify(apiKeyService, times(5)).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), eq("anthropic:kiro"), eq(2), any());
        // The relay was held to the 4,096 it always was, where the retry that Kiro serves is given the default output (D14, D15):
        // the client that was held back at the relay's bound is not held to it again.
        assertThat(relayDispatches.getFirst().forwardedOutputLimit()).isEqualTo(RELAY_OUTPUT);
        assertThat(kiroPayloads).hasSize(5).allSatisfy(payload ->
            assertThat(payload.path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(DEFAULT_OUTPUT));
    }

    /**
     * The operator paid for the answer that was held back, and the row of the attempt says what the relay reported of it (D11)
     * in columns of their own: the tokens and the credits of the row are the user's usage and charge, which are nothing.
     */
    @Test
    void theUsageTheRelayReportedOfAnAnswerHeldBackIsOnItsLogRowApartFromTheUsageOfTheUser() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAnswerWithheldException("The source's answer was cut off before it could be used",
            new PlatformUsage(60_000, 4_096, BigDecimal.ZERO, 0, 0, 0)));

        assertThatThrownBy(() -> responses(codexTurn(), "codex-session-1")).isInstanceOf(ApiException.class);

        StatsService.RequestLog row = onlyLog();
        assertThat(row.stopReason()).isEqualTo("WITHHELD");
        assertThat(row.success()).isFalse();
        assertThat(row.inputTokens()).isZero();
        assertThat(row.outputTokens()).isZero();
        assertThat(row.credits()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.withheldInputTokens()).isEqualTo(60_000L);
        assertThat(row.withheldOutputTokens()).isEqualTo(4_096L);
        // Nothing is charged, and the reservation is released for good.
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        verify(apiKeyService, never()).accountUsage(any(), anyString(), any(), anyLong(), anyLong());
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    /** A held back answer whose usage is not known leaves the columns empty, as a failure of any other kind does. */
    @Test
    void aHeldBackAnswerWithoutAUsageAndAFailureOfAnotherKindLeaveTheWithheldColumnsEmpty() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAnswerWithheldException("The source stopped its answer with its content filter"));
        assertThatThrownBy(() -> responses(codexTurn(), "codex-session-1")).isInstanceOf(ApiException.class);
        assertThat(onlyLog().withheldInputTokens()).isNull();
        assertThat(onlyLog().withheldOutputTokens()).isNull();
    }

    @Test
    void anInterruptedRelayLeavesTheWithheldColumnsEmpty() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> responses(codexTurn(), "codex-session-1")).isInstanceOf(ApiException.class);

        assertThat(onlyLog().stopReason()).isEqualTo("INTERRUPTED");
        assertThat(onlyLog().withheldInputTokens()).isNull();
        assertThat(onlyLog().withheldOutputTokens()).isNull();
    }

    /**
     * The limit a request that names none is held to, the default output of the gateway (D14), is the gateway's for the native
     * sources: Kiro is sent it as its {@code maxTokens} (it is the bound the reservation is sized from). Codex accounts, which drop
     * the field, are the only ones that are not held to it. A relay whose mapping states no maximum output is given the 4,096 it was
     * given before the gateway chose a default (D15, design 7.10): the gateway does not know what its upstream writes.
     */
    @Test
    void kiroIsHeldToTheDefaultOutputLimitAndTheRelayToTheFourThousandWhenTheClientNamesNone() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        busy.add("relay-1");
        responses(codexTurn().put("stream", false));
        responses(codexTurn().put("stream", false).put("max_output_tokens", 20_000));

        assertThat(lastLimits.maxOutputTokens()).isEqualTo(RELAY_OUTPUT);
        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(RELAY_OUTPUT);
        assertThat(kiroPayloads).hasSize(2);
        assertThat(kiroPayloads.get(0).path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
        assertThat(kiroPayloads.get(1).path("inferenceConfig").path("maxTokens").asInt()).as("the client's own limit is kept").isEqualTo(20_000);
    }

    /** A model the catalog does not list has no maximum to be held to: it keeps the limit the gateway has always given it, whatever the default is. */
    @Test
    void aModelTheCatalogDoesNotListIsHeldToTheFallbackLimitWhenTheClientNamesNone() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false).put("model", "claude-unlisted-9"));

        // The relay has no mapping for it, so Kiro serves.
        assertThat(relayDispatches).isEmpty();
        assertThat(kiroPayloads.getFirst().path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(4096);
    }

    /**
     * The reservation is sized from the same limit: a request that names none reserves the output of the default, and one that
     * names its own limit the output of that.
     */
    @Test
    void theReservationAssumesTheLimitTheRequestIsHeldToAndIsAsBigAsTheDefaultWhenTheClientNamesNone() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        responses(codexTurn().put("stream", false).put("max_output_tokens", 2000));

        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService, times(2)).reserveUsage(any(), anyString(), reserved.capture(), anyString());
        // $15/M out: Kiro's tier, at 0.6 the tier that is sent the default, reserves about 0.144 for 16,000 tokens, and the relay's
        // tier at the full price about 0.03 for the 2,000 the client wrote, plus the same input.
        assertThat(reserved.getAllValues().get(0)).isGreaterThan(new BigDecimal("0.144")).isLessThan(new BigDecimal("0.16"));
        assertThat(reserved.getAllValues().get(1)).isGreaterThan(new BigDecimal("0.03")).isLessThan(new BigDecimal("0.1"));
    }

    /**
     * The in-flight output tokens an account is admitted for stay what they were (4,096) whenever the limit of the request is
     * the gateway's, whichever source is asked: the cap on an account's in-flight tokens is an existing one, and the default
     * is for the reservation and the body. A limit the client wrote is held for as written.
     */
    @Test
    void admissionIsAskedForTheFourThousandItAlwaysWasWhenTheClientNamesNoLimitForRelayAndKiroAlike() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        responses(codexTurn().put("stream", false).put("max_output_tokens", 20_000));
        busy.add("relay-1");
        responses(codexTurn().put("stream", false));

        ArgumentCaptor<AdmissionRequest> asked = ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(admissionStore, times(4)).acquire(asked.capture());
        assertThat(asked.getAllValues()).extracting(AdmissionRequest::accountId, AdmissionRequest::reservedOutputTokens)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("relay-1", 4096L),
                org.assertj.core.groups.Tuple.tuple("relay-1", 20_000L),
                org.assertj.core.groups.Tuple.tuple("relay-1", 4096L),
                org.assertj.core.groups.Tuple.tuple("kiro-1", 4096L));
        // The body Kiro is sent and the reservation are the default, the relay's body is its own 4,096 (D15).
        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(RELAY_OUTPUT);
        assertThat(kiroPayloads.getFirst().path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
    }

    /**
     * The default is the operator's to set (STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS): it is the one number that Kiro is sent and that
     * the reservation is sized from, however high it is set (the models of this test write 64,000). A relay whose mapping states no
     * maximum output is not given it (D15).
     */
    @Test
    void theDefaultOutputTheOperatorSetIsTheOneKiroAndTheReservationAreHeldToAndAnUnboundedRelayStaysAtFourThousand() {
        defaultOutputTokens = 32_000;
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        busy.add("relay-1");
        responses(codexTurn().put("stream", false));

        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(RELAY_OUTPUT);
        assertThat(lastLimits.maxOutputTokens()).isEqualTo(RELAY_OUTPUT);
        assertThat(kiroPayloads.getFirst().path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(32_000);
        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService, times(2)).reserveUsage(any(), anyString(), reserved.capture(), anyString());
        // $15/M out at Kiro's 0.6, the tier that is sent the default: about 0.288 for 32,000 tokens, plus the input. The relay's
        // tier is reserved for the 4,096 its relay is given (D15).
        assertThat(reserved.getAllValues()).allSatisfy(hold -> assertThat(hold).isGreaterThan(new BigDecimal("0.288")).isLessThan(new BigDecimal("0.31")));
        // Admission is asked for the 4,096 it always was.
        ArgumentCaptor<AdmissionRequest> asked = ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(admissionStore, times(3)).acquire(asked.capture());
        assertThat(asked.getAllValues()).extracting(AdmissionRequest::reservedOutputTokens).containsOnly(4096L);
    }

    /** A prompt about as long as a Codex session gets after a few dozen turns: an estimated {@code tokens}, in one text. */
    private ObjectNode promptOf(int tokens, Integer limit) {
        ObjectNode body = plain("a".repeat(tokens * 4), null);
        if (limit != null) {
            body.put("max_output_tokens", limit);
        }
        return body;
    }

    // ---- what a relay is given for a request that names no output limit (D15) ----

    /** {@link #owner} for a relay whose mapping of the model states {@code maxOutput} as its maximum output (null: none). */
    private SourceTierSnapshot ownerWithMaximumOutput(Integer maxOutput, PairGate... gates) {
        Member member = relayMember("relay-1");
        return SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(member), List.of(mapping(member, relay, 200_000, maxOutput)), List.of(gates));
    }

    private static MemberModel mapping(Member member, ProxyAccountEntity account, int window, Integer maxOutput) {
        MemberModel unverified = new MemberModel(member.vendor(), member.accountId(), "claude-sonnet-4.5", "relay-sonnet", window, 8192,
            maxOutput, true, null, null);
        return new MemberModel(member.vendor(), member.accountId(), "claude-sonnet-4.5", "relay-sonnet", window, 8192, maxOutput, true,
            Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, account));
    }

    /**
     * The relay is given the maximum its mapping states, and held to it (what it reports of its answer is bounded by it), where
     * the gateway chose the limit; Kiro and the reservation are still held to the default of the gateway.
     */
    @Test
    void aRelayWhoseMappingStatesAMaximumOutputIsGivenItAndHeldToIt() {
        configure(ownerWithMaximumOutput(8_192, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        busy.add("relay-1");
        responses(codexTurn().put("stream", false));

        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(8_192);
        assertThat(relayDispatches.getFirst().forwardedOutputLimit()).isEqualTo(8_192);
        assertThat(lastLimits.maxOutputTokens()).isEqualTo(8_192);
        assertThat(defaultOutputLimits).as("the client named none: the limit is the gateway's own").containsExactly(true);
        assertThat(kiroPayloads.getFirst().path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
        // The reservation covers whichever source serves, each as far as it is sent: the relay's tier its 8,192, Kiro's the default at
        // 0.6, the dearer; settlement is of what was served.
        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService, times(2)).reserveUsage(any(), anyString(), reserved.capture(), anyString());
        assertThat(reserved.getAllValues()).allSatisfy(hold -> assertThat(hold).isGreaterThan(new BigDecimal("0.144")).isLessThan(new BigDecimal("0.16")));
    }

    /** A maximum above the default is capped by it, and by the default the operator set, for the reservation covers no more. */
    @Test
    void aMaximumOutputAboveTheDefaultOfTheRequestIsCappedByIt() {
        configure(ownerWithMaximumOutput(64_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));
        defaultOutputTokens = 32_000;
        responses(codexTurn().put("stream", false));
        defaultOutputTokens = 100_000;
        responses(codexTurn().put("stream", false));

        // The model writes 64,000, which is the most any default comes to.
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 32_000L, 64_000L);
    }

    @Test
    void aMaximumOutputBelowFourThousandNinetySixIsWhatTheRelayIsGiven() {
        configure(ownerWithMaximumOutput(2_048, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false));

        assertThat(relayDispatches.getFirst().wireBody().path("max_tokens").asInt()).isEqualTo(2_048);
        assertThat(lastLimits.maxOutputTokens()).isEqualTo(2_048);
    }

    /** A limit the client wrote is its own, whatever the mapping says: it is the client's to size, as it always was. */
    @Test
    void aLimitTheClientWroteIsSentToTheRelayAsItIsWhateverItsMappingStates() {
        configure(ownerWithMaximumOutput(8_192, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn().put("stream", false).put("max_output_tokens", 20_000));
        responses(codexTurn().put("stream", false).put("max_output_tokens", 2_000));

        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(20_000L, 2_000L);
        assertThat(defaultOutputLimits).containsExactly(false, false);
    }

    /**
     * The window of the relay (200,000 here) holds the prompt alone when the relay is given the 4,096 it always was, and the prompt
     * and the maximum output of its mapping when it is given that: a turn that does not leave room for the maximum is still the
     * relay's, with the request it was given before the gateway chose a default, and a turn over the window is Kiro's, as before.
     */
    @Test
    void aRelayWhoseWindowCannotHoldItsMaximumOutputBesidesThePromptIsGivenTheFourThousandItHadBefore() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> room = responses(promptOf(170_000, null));
        ResponseEntity<?> tight = responses(promptOf(190_000, null));
        ResponseEntity<?> over = responses(promptOf(210_000, null));

        assertThat(dispatched).containsExactly("relay-1", "relay-1", "kiro-1");
        assertThat(room.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(tight.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(over.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
        // The fallback is the body the relay was given before D11, and nothing was learned or left out for the output.
        MemberModel mapping = mapping(relayMember("relay-1"), relay, 200_000, 16_000);
        assertThat(learnedLimits.refusedFrom(mapping)).isEmpty();
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    /**
     * A relay that counts the prompt higher than the gateway does and refuses the length of the request with its maximum output is
     * taught the size of the prompt beside 4,096: the estimate and the output beyond it (the prompt alone is not what it was refused
     * for). The request crosses to Kiro once, as it did; the relay is not asked a second time.
     */
    @Test
    void aRelayThatRefusesTheLengthOfARequestWithItsMaximumOutputIsTaughtThePromptBesideFourThousandAndKiroServesTheTurn() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayCounts("relay-1", 1.05, 200_000);

        ResponseEntity<?> response = responses(promptOf(183_000, null));

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        MemberModel mapping = mapping(relayMember("relay-1"), relay, 200_000, 16_000);
        assertThat(learnedLimits.refusedFrom(mapping).getAsLong()).as("the estimate of the prompt, and the 16,000 - 4,096 beside it")
            .isBetween(183_000L + 11_904L, 183_100L + 11_904L);
        // A smaller prompt is still the relay's, with the maximum if the window holds it.
        responses(promptOf(150_000, null));
        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "relay-1");
        assertThat(relayDispatches.getLast().forwardedOutputLimit()).isEqualTo(16_000L);
    }

    /**
     * What the relay refused with 16,000 it still serves with 4,096 for the prompts that the window holds beside that: the estimate of
     * the gateway is often low, and a prompt that did not fit beside the maximum output is not for that reason one the relay cannot
     * serve. A refusal of the 4,096 body is a refusal of the prompt, as it always was, and shuts the relay out of prompts that large.
     */
    @Test
    void aRelayThatRefusedTheMaximumOutputIsStillAskedForThePromptsItsWindowHoldsBesideFourThousandNinetySix() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayCounts("relay-1", 1.05, 200_000);

        responses(promptOf(183_000, null));
        // The relay counts 193,200 and the window holds that beside 4,096: it is the relay's, without the maximum.
        ResponseEntity<?> beside = responses(promptOf(184_000, null));
        // The relay counts 196,350 and the window does not hold that beside 4,096 either: refused, and taught as it always was.
        ResponseEntity<?> over = responses(promptOf(187_000, null));
        ResponseEntity<?> afterwards = responses(promptOf(187_500, null));
        ResponseEntity<?> smaller = responses(promptOf(185_000, null));

        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "relay-1", "relay-1", "kiro-1", "kiro-1", "relay-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L, 4_096L, 4_096L);
        assertThat(beside.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(over.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(afterwards.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(smaller.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        MemberModel mapping = mapping(relayMember("relay-1"), relay, 200_000, 16_000);
        assertThat(learnedLimits.refusedFrom(mapping).getAsLong()).isBetween(187_000L, 187_100L);
    }

    /**
     * What the refusal of the maximum output taught holds for a client that writes that output itself (another client of the
     * Responses entry, or the Chat or Messages entry of the same mapping): the body that was refused, or one that asks for more of
     * both, is not sent to the relay a second time and does not cross to Kiro after another refusal. The 4,096 body of the same
     * prompt is still the relay's.
     */
    @Test
    void aBodyThatAsksForAsMuchOutputAsTheOneTheRelayRefusedIsNotSentToItAgainWhoeverWroteTheLimit() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayCounts("relay-1", 1.05, 200_000);

        responses(promptOf(183_000, null));
        ResponseEntity<?> same = responses(promptOf(183_000, 16_000));
        ResponseEntity<?> more = responses(promptOf(183_000, 32_000));
        ResponseEntity<?> beside = responses(promptOf(183_000, null));

        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "kiro-1", "kiro-1", "relay-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
        assertThat(same.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(more.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(beside.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        // The relay was not refused again, so nothing was learned beyond the first refusal: the 4,096 body of this size is the relay's.
        MemberModel mapping = mapping(relayMember("relay-1"), relay, 200_000, 16_000);
        assertThat(learnedLimits.refusedFrom(mapping).getAsLong()).isBetween(183_000L + 11_904L, 183_100L + 11_904L);
    }

    /**
     * A relay that refuses the length of the body with its maximum output, with no native account to cross to, is not told to the
     * client as "prompt is too long": the identical request is sent the 4,096 body the refusal taught and is served, and a client
     * that was told the prompt does not fit would shorten, lossily, a conversation the relay takes (Codex ends the turn on a 400).
     * The client is told to try again, which is what the identical request is told next.
     */
    @Test
    void aRelayThatRefusesTheMaximumOutputWithNoNativeToCrossToLeavesTheClientToTryAgainAndTheRetryIsTheRelays() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay);
        relayCounts("relay-1", 1.05, 200_000);

        assertThatThrownBy(() -> responses(promptOf(183_000, null)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });
        ResponseEntity<?> retry = responses(promptOf(183_000, null));

        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        // The refusal released the reservation for good, and the retry was charged for what it was served.
        verify(apiKeyService, times(1)).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    /**
     * Anthropic says a prompt that fits the window but not beside max_tokens in words of its own ("input length and `max_tokens`
     * exceed context limit"), which a relay in front of it passes on. For a body whose limit the gateway chose it is the same refusal
     * for length as the words of vLLM: learned, answered with a retry where there is nowhere to cross to, and the retry is the relay's
     * at 4,096. Taken for a conversion the relay cannot make, it would be neither, and every turn of that size would be sent the wide
     * body, and refused, again.
     */
    @Test
    void aRelayThatRefusesTheMaximumOutputInTheWordsOfAnthropicIsLearnedAndTheRetryIsTheRelays() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay);
        relayCounts("relay-1", 1.05, 200_000, true);

        assertThatThrownBy(() -> responses(promptOf(183_000, null)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });
        ResponseEntity<?> retry = responses(promptOf(183_000, null));
        ResponseEntity<?> again = responses(promptOf(183_000, null));

        assertThat(dispatched).containsExactly("relay-1", "relay-1", "relay-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L, 4_096L);
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(again.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        MemberModel mapping = mapping(relayMember("relay-1"), relay, 200_000, 16_000);
        assertThat(learnedLimits.refusedFrom(mapping).getAsLong()).isBetween(183_000L + 11_904L, 183_100L + 11_904L);
    }

    /** With a relay and Kiro, the relay's refusal in the words of Anthropic is learned too: the turn after it is not sent the wide body again. */
    @Test
    void aRelayThatRefusesTheMaximumOutputInTheWordsOfAnthropicIsNotSentTheSameBodyAgainWhenKiroServedIt() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayCounts("relay-1", 1.05, 200_000, true);

        ResponseEntity<?> first = responses(promptOf(183_000, null));
        ResponseEntity<?> second = responses(promptOf(183_000, null));

        assertThat(first.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(second.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
    }

    /**
     * Anthropic's words say a prompt did not fit beside the output asked for. For a limit the client wrote that is no number this
     * node chose, and learning the prompt alone from it would keep the relay, for six hours, from every prompt that large, the
     * 4,096 bodies of the same mapping included: it stays what it was before D15, a 400 of the converted request that teaches
     * nothing and crosses to Kiro once.
     */
    @Test
    void aRelayThatRefusesALimitTheClientWroteInTheWordsOfAnthropicTeachesNothingAndTheSmallerRequestIsStillTheRelays() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayCounts("relay-1", 1.05, 200_000, true);

        ResponseEntity<?> refused = responses(promptOf(183_000, 20_000));
        ResponseEntity<?> smaller = responses(promptOf(183_000, 2_000));

        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "relay-1");
        assertThat(refused.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(smaller.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        MemberModel mapping = mapping(relayMember("relay-1"), relay, 200_000, 16_000);
        assertThat(learnedLimits.refusedFrom(mapping)).isEmpty();
        assertThat(learnedLimits.refusedAsMuch(mapping, 183_000, 20_000)).isFalse();
        assertThat(logs().getFirst().stopReason()).as("a conversion the relay would not take, as before").isEqualTo("CONVERSION_REJECTED");

        // The body the gateway chose the limit of is the other case: the same words are learned from it, and Kiro serves that turn.
        ResponseEntity<?> gateways = responses(promptOf(183_000, null));

        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "relay-1", "relay-1", "kiro-1");
        assertThat(gateways.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(learnedLimits.refusedFrom(mapping).getAsLong()).isBetween(183_000L + 11_904L, 183_100L + 11_904L);
    }

    /** With nothing native to cross to, the same refusal is told to the client as the relay's own 400, not as a prompt that is too long. */
    @Test
    void aRelayThatRefusesALimitTheClientWroteInTheWordsOfAnthropicIsToldToTheClientAsTheRefusalItIsAndNotAskedLess() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay);
        relayCounts("relay-1", 1.05, 200_000, true);

        assertThatThrownBy(() -> responses(promptOf(183_000, 20_000)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getMessage()).startsWith("Source error 400").doesNotContain("prompt is too long");
            });
        ResponseEntity<?> smaller = responses(promptOf(183_000, 2_000));

        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(smaller.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(learnedLimits.refusedFrom(mapping(relayMember("relay-1"), relay, 200_000, 16_000))).isEmpty();
    }

    /** The same for a request pinned to the relay by the suffix (which the Kiro account beside it does not open): nothing to cross to. */
    @Test
    void aRelayThatRefusesTheMaximumOutputForARequestPinnedToItLeavesTheClientToTryAgainAndTheRetryIsTheRelays() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayCounts("relay-1", 1.05, 200_000);

        assertThatThrownBy(() -> responses(promptOf(183_000, null).put("model", "claude-sonnet-4.5@relay")))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
            });
        ResponseEntity<?> retry = responses(promptOf(183_000, null).put("model", "claude-sonnet-4.5@relay"));

        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    /**
     * The crossing of a refusal finds nothing when the tier below holds relays alone (another relay would be given the same
     * request, design 7.8), and the client is told to try again then too: the answer of the crossing that found no account is
     * the answer of a request that has nowhere to go, which is the same for the refusal of the maximum output.
     */
    @Test
    void aRelayThatRefusesTheMaximumOutputWithOnlyRelaysBelowItLeavesTheClientToTryAgainAndTheRetryIsTheRelays() {
        Member first = relayMember("relay-1");
        Member second = new Member("anthropic", "relay-2", "second", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
        ProxyAccountEntity other = account("relay-2", "OpenAI-compatible", "api-key", "https://relay-2.example/v1");
        configure(SourceTierSnapshot.of(7L, List.of(vendor()), List.of(tier("relay", 1, "1"), tier("second", 2, "0.8")),
            List.of(first, second), List.of(mapping(first, relay, 200_000, 16_000), mapping(second, other, 200_000, null)),
            List.of(gate("RESPONSES", "KEYS", "key-1"))));
        add(relay, other);
        relayCounts("relay-1", 1.05, 200_000);

        assertThatThrownBy(() -> responses(promptOf(183_000, null)))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        ResponseEntity<?> retry = responses(promptOf(183_000, null));

        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
        assertThat(retry.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
    }

    /**
     * Only a refusal of a body that asked for more than 4,096 is told as a retry: the retry is sent less. A relay that refuses the
     * 4,096 body too (or a body that never asked for more) is refused again, and the client is told the prompt is too long, as it
     * always was where nothing is left to cross to.
     */
    @Test
    void aRelayThatRefusesTheFourThousandBodyTooIsToldToTheClientAsTheRefusalItIs() {
        configure(ownerWithMaximumOutput(16_000, gate("RESPONSES", "KEYS", "key-1")));
        add(relay);
        relayCounts("relay-1", 1.2, 200_000);

        assertThatThrownBy(() -> responses(promptOf(183_000, null)))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> responses(promptOf(183_000, null)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getMessage()).contains("prompt is too long");
            });

        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(16_000L, 4_096L);
    }

    @Test
    void aRelayWithNoMaximumOutputThatRefusesTheLengthWithNoNativeToCrossToIsToldToTheClientAsTheRefusalItIs() {
        configure(ownerWithMaximumOutput(null, gate("RESPONSES", "KEYS", "key-1")));
        add(relay);
        relayCounts("relay-1", 1.2, 200_000);

        assertThatThrownBy(() -> responses(promptOf(183_000, null)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getMessage()).contains("prompt is too long");
            });

        assertThat(relayDispatches).extracting(TierDispatch::forwardedOutputLimit).containsExactly(4_096L);
    }

    /**
     * A relay that counts a prompt of the same estimate higher, in the words of vLLM, which the mapper reads as a refusal for length;
     * what it refuses is what the prompt and the output it is told come to over {@code window}.
     */
    private void relayCounts(String accountId, double counts, int window) {
        relayCounts(accountId, counts, window, false);
    }

    /** The same, in the words of Anthropic when {@code anthropicWords} (a prompt that fits the window, but not beside max_tokens). */
    private void relayCounts(String accountId, double counts, int window, boolean anthropicWords) {
        relayRefuses.put(accountId, dispatch -> {
            long counted = Math.round(RelayUsageNormalizer.estimateInputTokens(dispatch.wireBody()) * counts);
            long output = dispatch.forwardedOutputLimit();
            if (counted + output <= window) {
                return null;
            }
            String refusal = anthropicWords
                ? error(null, "input length and `max_tokens` exceed context limit: " + counted + " + " + output
                    + " > " + window + ", decrease input length or `max_tokens` and try again")
                : error("context_length_exceeded", "maximum context length is " + window
                    + " tokens ... (" + counted + " in the messages, " + output + " in the completion)");
            return relayErrors.fromResponse(400, refusal, null, accountId, "sk-secret");
        });
    }

    /**
     * What a native source does with a turn longer than the request timeout (STARRY_REQUEST_TIMEOUT, 300 s) is not new with
     * the default output, which lets a Codex turn run for minutes: Kiro and the official keys are asked for the whole answer
     * under that timeout, and one that ran out after the request was sent may still be generated and billed upstream. So the
     * reservation, which is the default output at the price of Kiro's tier, is left for review and not released, and nothing is charged; the operator
     * is told in the operations manual to raise the timeout together with the default (STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS).
     */
    @Test
    void aKiroTurnThatRunsOutOfTheRequestTimeoutKeepsTheReservationOfTheDefaultOutputForReview() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        kiroAnswers.put("kiro-1", () -> Mono.error(new TimeoutException("no answer within the request timeout")));

        assertThatThrownBy(() -> responses(codexTurn().put("stream", false))).isInstanceOf(ApiException.class);

        assertThat(dispatched).containsExactly("kiro-1");
        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).reserveUsage(any(), anyString(), reserved.capture(), anyString());
        assertThat(reserved.getValue()).as("the default at Kiro's 0.6, about 0.144 of $15/M, plus the input")
            .isGreaterThan(new BigDecimal("0.144")).isLessThan(new BigDecimal("0.16"));
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /**
     * The relay did what it was asked, so its account is not counted against (the error count and the automatic test that two
     * failures in a row start), and admission is told it answered: nothing that could cool it down for the other sessions.
     */
    @Test
    void anAnswerTheGatewayHeldBackIsNoFailureOfTheRelaysAccountAndAdmissionIsToldOfAnAnswer() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAnswerWithheldException("The source stopped its answer with its content filter"));

        for (int session = 0; session < 4; session++) {
            int number = session;
            assertThatThrownBy(() -> responses(codexTurn(), "codex-session-" + number))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        verify(accountPool, never()).recordRequestFailure(eq("relay-1"), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class));
        ArgumentCaptor<AccountAdmissionStore.AdmissionResult> reconciled = ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionResult.class);
        verify(admissionStore, times(4)).reconcile(reconciled.capture());
        assertThat(reconciled.getAllValues()).allSatisfy(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.failureScope()).isNotEqualTo(AccountFailureScope.TRANSPORT);
            assertThat(result.cooldown()).isEqualTo(Duration.ZERO);
        });
        // The relay is asked again by a session that it did not answer for: nothing of its state was touched.
        assertThat(logs()).hasSize(4).allSatisfy(log -> {
            assertThat(log.success()).isFalse();
            assertThat(log.statusCode()).isEqualTo(502);
            assertThat(log.stopReason()).isEqualTo("WITHHELD");
        });
        relayAnswers.clear();
        dispatched.clear();
        assertThat(finalResponse(responses(codexTurn().put("stream", false), "codex-session-9")).path("status").asString()).isEqualTo("completed");
        assertThat(dispatched).containsExactly("relay-1");
    }

    /** A breakdown is a failure of the relay all the same, and the answer the gateway held back has not changed that. */
    @Test
    void aBreakdownOfTheRelayStillCountsAgainstItsAccount() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> responses(codexTurn(), "codex-session-1")).isInstanceOf(ApiException.class);

        verify(accountPool).recordRequestFailure(eq("relay-1"), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class));
        ArgumentCaptor<AccountAdmissionStore.AdmissionResult> reconciled = ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionResult.class);
        verify(admissionStore).reconcile(reconciled.capture());
        assertThat(reconciled.getValue().success()).isFalse();
        assertThat(reconciled.getValue().failureScope()).isEqualTo(AccountFailureScope.TRANSPORT);
        assertThat(logs().getFirst().stopReason()).isEqualTo("INTERRUPTED");
    }

    /** What the answer is rendered as is the answer's; a call that ended its turn normally is rendered as it always was. */
    @Test
    void anAnswerThatEndedByItselfKeepsItsCallsWhateverTheClientNamed() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(execCall()));

        JsonNode answer = finalResponse(responses(codexTurn().put("max_output_tokens", 2000).put("stream", false)));

        assertThat(answer.path("status").asString()).isEqualTo("completed");
        assertThat(answer.path("output")).extracting(item -> item.path("type").asString()).containsExactly("function_call");
    }

    // ---- the chain a relay answered has no owner ----

    @Test
    void aResponseOfARelayIsRememberedWithoutAnOwnerAndNoOneIsRecordedAsOwningIt() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        String id = finalResponse(responses(codexTurn())).path("id").asString();

        // The history is held, so the chain can be continued from it; but no account is the chain's owner (design 4.8).
        assertThat(stateStore.canExpand(id)).isTrue();
        assertThat(stateStore.ownerAccountId(id)).isNull();
        verify(ownerStore, never()).remember(anyString(), anyString());
    }

    @Test
    void aResponseOfKiroIsRememberedWithItsOwnerAsItAlwaysWas() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");

        String id = finalResponse(responses(codexTurn())).path("id").asString();

        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(stateStore.ownerAccountId(id)).isEqualTo("kiro-1");
        verify(ownerStore).remember(id, "kiro-1");
    }

    @Test
    void theNextTurnOfAChainTheRelayAnsweredIsReplayedFromTheHistoryTheGatewayHolds() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(execCall()));
        JsonNode first = finalResponse(responses(codexTurn()));

        relayAnswers.put("relay-1", () -> Mono.just(RELAY_ANSWER));
        // The tools and the instructions are the client's to repeat; the history is the gateway's.
        ObjectNode second = codexTurn().put("stream", false);
        second.put("previous_response_id", first.path("id").asString());
        second.putArray("input").addObject().put("type", "function_call_output").put("call_id", CALL_ID).put("output", "hello");
        ResponseEntity<?> response = responses(second);

        // The relay is the chain's again, and is sent the whole conversation: the call it made and what became of it.
        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        JsonNode body = relayDispatches.getLast().wireBody();
        assertThat(body.has("previous_response_id")).isFalse();
        JsonNode messages = body.path("messages");
        ArrayNode assistant = (ArrayNode) messages.get(messages.size() - 2).path("tool_calls");
        assertThat(messages.get(messages.size() - 2).path("role").asString()).isEqualTo("assistant");
        assertThat(assistant).hasSize(1);
        assertThat(assistant.get(0).path("id").asString()).isEqualTo(CALL_ID);
        assertThat(assistant.get(0).path("function").path("name").asString()).isEqualTo("exec_command");
        assertThat(messages.get(messages.size() - 1).path("role").asString()).isEqualTo("tool");
        assertThat(messages.get(messages.size() - 1).path("tool_call_id").asString()).isEqualTo(CALL_ID);
        assertThat(messages.get(messages.size() - 1).path("content").asString()).isEqualTo("hello");
    }

    @Test
    void aCodexSessionThatSendsItsWholeHistoryEveryTurnIsServedByTheRelayEveryTurnWithoutTheReasoningItCarries() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult(
            "", "I should run it", List.of(execToolCall()), USAGE, StopReason.TOOL_USE, UsageSource.REPORTED, List.of())));
        JsonNode first = finalResponse(responses(codexTurn().put("stream", false), "codex-session-1"));
        // What the client was given is what it sends back with the result of the call: the reasoning item too.
        assertThat(first.path("output").path(0).path("type").asString()).isEqualTo("reasoning");

        relayAnswers.put("relay-1", () -> Mono.just(RELAY_ANSWER));
        ObjectNode second = codexTurn().put("stream", false);
        first.path("output").forEach(item -> second.withArray("input").add(item));
        second.withArray("input").addObject().put("type", "function_call_output").put("call_id", CALL_ID).put("output", "hello");
        ResponseEntity<?> response = responses(second, "codex-session-1");

        assertThat(dispatched).containsExactly("relay-1", "relay-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        JsonNode body = relayDispatches.getLast().wireBody();
        assertThat(body.toString()).doesNotContain("I should run it", "reasoning");
        assertThat(body.path("messages")).extracting(message -> message.path("role").asString())
            .containsExactly("system", "system", "user", "assistant", "tool");
        assertThat(body.path("messages").get(3).path("tool_calls").get(0).path("id").asString()).isEqualTo(CALL_ID);
    }

    @Test
    void aChainThatFellToKiroOnItsFirstTurnStaysThereForTheTurnsAfterIt() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        String first = idOf(responses(plain("start the task", null)));
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:relay");

        // The relay is free again, but the chain was pushed down and stays where it is (D4): the binding follows the
        // response of each turn, whose id is the name of the session for the next one.
        busy.clear();
        String second = idOf(responses(plain("second turn", first)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        String third = idOf(responses(plain("third turn", second)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        assertThat(third).isNotEqualTo(second);
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "kiro-1");
        verify(xpDispatcher, never()).dispatch(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * A chain that fell to Kiro and finds Kiro without an account does not climb back to the relay above it, as it did not before the
     * reservation of a tier of relays alone followed what the relay is sent (D15): it is told what Kiro said (D4) and stays where it
     * is, instead of being charged the relay's factor for the turn and having its binding moved up to the relay.
     */
    @Test
    void aChainThatFellToKiroDoesNotClimbBackToTheRelayWhenKiroHasNoAccountForTheTurn() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        String first = idOf(responses(plain("start the task", null)));
        assertThat(dispatched).containsExactly("kiro-1");
        busy.clear();
        kiroAnswers.put("kiro-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        assertThatThrownBy(() -> responses(plain("second turn", first)))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        kiroAnswers.remove("kiro-1");
        responses(plain("third turn", first));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "kiro-1");
    }

    /**
     * What a chain's session was bound to is bound for the chain, not for the model of the turn the binding was made in: a turn
     * for another model does not cut the chain from its tier, and the turn after it, for the first model again, does not start
     * from the relay it was pushed down from.
     */
    @Test
    void aChainThatFellToKiroStaysThereThroughATurnForAnotherModel() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        String first = idOf(responses(plain("start the task", null)));
        assertThat(startedFrom()).isEqualTo("anthropic:relay");
        busy.clear();

        String second = idOf(responses(plain("claude-opus-4.5", "a question for another model", first)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        String third = idOf(responses(plain("claude-sonnet-4.5", "back to the first model", second)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        String fourth = idOf(responses(plain("claude-sonnet-4.5", "and on", third)));

        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(fourth).isNotEqualTo(third);
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "kiro-1", "kiro-1");
    }

    /**
     * A turn of a model whose vendor has no tiers is no tier's, but it is a turn of the chain: the response it produces is
     * recorded as the chain's session all the same, or the turn after it would be a session of its own, and a session that fell
     * to Kiro would go back to the relay without having been idle.
     */
    @Test
    void aChainThatFellToKiroStaysThereThroughATurnForAModelOfAVendorWithoutTiers() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        untieredOfficial();
        busy.add("relay-1");
        String first = idOf(responses(plain("start the task", null)));
        assertThat(startedFrom()).isEqualTo("anthropic:relay");
        busy.clear();
        String second = idOf(responses(plain("claude-sonnet-4.5", "second", first)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        String third = idOf(responses(plain("gpt-5.4", "a question for a gpt model", second)));
        assertThat(dispatched).endsWith("openai-1");
        String fourth = idOf(responses(plain("claude-sonnet-4.5", "back to claude", third)));

        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "openai-1", "kiro-1");
        responses(plain("claude-sonnet-4.5", "and on", fourth));
        assertThat(dispatched).endsWith("kiro-1");
        assertThat(dispatched).doesNotContain("relay-1");
    }

    /** The same through a vendor that is tiered but keeps no session in a tier and has no relay open to the key: no plan names the session. */
    @Test
    void aChainThatFellToKiroStaysThereThroughATurnForAModelOfATieredVendorThatKeepsNoSessions() {
        configure(ownerPlusTieredOpenAi(false));
        add(relay, kiro("kiro-1"), account("openai-1", "OpenAI", "api-key", null));
        busy.add("relay-1");
        String first = idOf(responses(plain("start the task", null)));
        busy.clear();
        String second = idOf(responses(plain("claude-sonnet-4.5", "second", first)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        ResponseEntity<?> gpt = responses(plain("gpt-5.4", "a question for a gpt model", second));
        assertThat(gpt.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        String fourth = idOf(responses(plain("claude-sonnet-4.5", "back to claude", idOf(gpt))));

        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(fourth).isNotNull();
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "openai-1", "kiro-1");
    }

    /** The interruption of a relay holds for the whole chain, and a turn on a vendor without tiers in between does not end it. */
    @Test
    void theExclusionOfARelayHoldsThroughATurnForAModelOfAVendorWithoutTiers() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        untieredOfficial();
        String first = idOf(responses(plain("start the task", null)));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));
        assertThatThrownBy(() -> responses(plain("second turn", first))).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        String second = idOf(responses(plain("second turn", first)));
        dispatched.clear();

        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        String third = idOf(responses(plain("gpt-5.4", "a question for a gpt model", second)));
        assertThat(dispatched).containsExactly("openai-1");
        dispatched.clear();

        // Forty seconds after the interruption the relay may be generating the answer it broke off still.
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        String fourth = idOf(responses(plain("claude-sonnet-4.5", "back to claude, 40 s after the interruption", third)));
        assertThat(dispatched).containsExactly("kiro-1");

        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(81).toNanos());
        responses(plain("claude-sonnet-4.5", "much later", fourth));
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void theExclusionOfARelayHoldsThroughATurnForAModelOfATieredVendorThatKeepsNoSessions() {
        configure(ownerPlusTieredOpenAi(false));
        add(relay, kiro("kiro-1"), account("openai-1", "OpenAI", "api-key", null));
        String first = idOf(responses(plain("start the task", null)));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));
        assertThatThrownBy(() -> responses(plain("second turn", first))).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        String second = idOf(responses(plain("second turn", first)));
        dispatched.clear();

        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        ResponseEntity<?> gpt = responses(plain("gpt-5.4", "a question for a gpt model", second));
        assertThat(gpt.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        dispatched.clear();

        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        responses(plain("claude-sonnet-4.5", "back to claude, 40 s after the interruption", idOf(gpt)));
        assertThat(dispatched).containsExactly("kiro-1");
    }

    /** What the chain is called is the same node's memory whatever served the turn, and it is only the chain's: nothing is written for a request that names none. */
    @Test
    void aTurnWithNoChainAndNoNameOnAVendorWithoutTiersLeavesNothingToFollow() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        untieredOfficial();

        String gpt = idOf(responses(plain("gpt-5.4", "a first question", null)));

        verify(bindings, never()).follow(anyString(), anyString());
        assertThat(bindings.sessionOf(TierSessionKeys.ofResponse("key-1", gpt))).isEqualTo(TierSessionKeys.ofResponse("key-1", gpt));
    }

    /**
     * The chain map is for the turns of a session that tiers keep whole. A deployment where no vendor has tiers in use, none
     * configured or every vendor switched off, runs the untiered path as it always did and writes nothing: not for a Codex session,
     * which finds itself again by its header, and not for a chain, which no tier will ever continue.
     */
    @Test
    void aDeploymentWithNoTierInUseKeepsNothingOfTheChainsItAnswers() {
        untieredOfficial();
        for (SourceTierSnapshot none : List.of(
            SourceTierSnapshot.EMPTY,
            SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", false, false, false, true, 16, true, 7L)),
                List.of(tier("kiro", 1, "1", AccountAllocationCategory.KIRO)), List.of(), List.of(), List.of()))) {
            configure(none);

            String first = idOf(responses(plain("gpt-5.4", "a first question", null), "codex-session-1"));
            String second = idOf(responses(plain("gpt-5.4", "and a second", first), "codex-session-1"));
            String third = idOf(responses(plain("gpt-5.4", "a chain without a header", second)));

            assertThat(third).isNotNull();
            verify(bindings, never()).follow(anyString(), anyString());
        }
        // Where some vendor has tiers, the same turns are recorded (aChainThatFellToKiroStaysThereThroughATurnForAModelOfAVendorWithoutTiers).
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        String first = idOf(responses(plain("gpt-5.4", "a first question", null), "codex-session-1"));
        verify(bindings).follow(TierSessionKeys.ofResponse("key-1", first), TierSessionKeys.ofCodexSession("key-1", "codex-session-1"));
    }

    @Test
    void aChainThatIsIdleForTheAffinityTtlStartsFromTheBestTierAgain() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        String first = idOf(responses(plain("start the task", null)));
        busy.clear();
        String second = idOf(responses(plain("second turn", first)));
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        clock.addAndGet(Duration.ofMinutes(10).plusSeconds(1).toNanos());
        responses(plain("a long time after", second));

        assertThat(startedFrom()).isEqualTo("anthropic:relay");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "relay-1");
    }

    @Test
    void aChainThatWasNeverPushedDownStartsFromTheRelayEveryTurn() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        String first = idOf(responses(plain("start the task", null)));
        String second = idOf(responses(plain("second turn", first)));
        responses(plain("third turn", second));

        assertThat(dispatched).containsExactly("relay-1", "relay-1", "relay-1");
        assertThat(startedFrom()).isEqualTo("anthropic:relay");
    }

    @Test
    void aChainThatThisNodeCouldNotExpandIsServedByKiroAsItAlwaysWasAndIsAConflictWhenKiroIsNotThere() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = responses(plain("continue", "resp_from_another_node"));

        // The relay would be given the last turn alone: it is not asked, and the chain is Kiro's as it was.
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");

        cached.removeIf(account -> account.getAccountId().equals("kiro-1"));
        assertThatThrownBy(() -> responses(plain("continue", "resp_from_another_node")))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("RESPONSE_CHAIN_NOT_PORTABLE");
            });
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aChainTheRelayAnsweredIsNeverOwnedByItForARouteThatKnowsNoTiers() {
        // The openai vendor with a relay as its first tier; the next turn of the chain meets a deployment without tiers, as
        // after a switch is turned off or on a node that does not run them.
        configure(openAiRelayBesideOfficialKeys());
        ProxyAccountEntity official = account("openai-1", "OpenAI", "api-key", null);
        add(relay, official);
        ObjectNode first = objectMapper.createObjectNode();
        first.put("model", "gpt-5.4").put("input", "list the files").put("stream", false);

        String id = idOf(facade(offeringOpenAi()).responses(first, exchange(null)).block());

        assertThat(dispatched).containsExactly("relay-1");
        assertThat(stateStore.ownerAccountId(id)).isNull();
        verify(ownerStore, never()).remember(anyString(), anyString());

        dispatched.clear();
        when(accountPool.selectOpenAiApiKeyAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(official));
        ObjectNode next = objectMapper.createObjectNode();
        next.put("model", "gpt-5.4").put("input", "and then").put("stream", false).put("previous_response_id", id);
        ResponseEntity<?> response = untieredFacade().responses(next, exchange(null)).block();

        // The chain is replayed on an account of the pool; the relay, which never owned it, is not the account asked for it.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dispatched).containsExactly("openai-1");
        verify(accountPool, never()).selectOpenAiAccountByIdForPool(anyString(), any(AccountPoolRequestContext.class));
        verify(xpDispatcher, times(1)).dispatch(any(), any(), any(), any(), anyBoolean());
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiClient).completeResponses(eq(official), sent.capture(), anyString());
        assertThat(sent.getValue().has("previous_response_id")).isFalse();
        assertThat(sent.getValue().path("input").toString()).contains("list the files", "from the relay", "and then");
    }

    // ---- the relay cannot serve, and the request goes on to Kiro ----

    @Test
    void aRelayThatDoesNotServeTheModelIsLeftForKiroAndTheResponseIsKiros() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "no such model"), null, "relay-1", "sk-secret"));

        ResponseEntity<?> response = responses(codexTurn());

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        // Billed at the tier that served: 0.039 at 0.6.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.0234")) == 0), eq(8_000L), eq(1_000L), eq("anthropic:kiro"), eq(2),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(new BigDecimal("0.6")) == 0));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        // The answer of Kiro is remembered with Kiro as its owner.
        String id = finalResponse(response).path("id").asString();
        verify(ownerStore).remember(id, "kiro-1");
    }

    @Test
    void aRelayThatRejectsItsOwnKeyIsLeftForKiroAndRestsAndTheClientIsNeverTold401() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(401, error("invalid_api_key", "Incorrect API key provided: sk-secret"),
            null, "relay-1", "sk-secret"));

        ResponseEntity<?> response = responses(codexTurn());

        // D5: the key of the relay is the operator's to mend; the request goes on to the next source.
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        assertThat(logs().getFirst().stopReason()).isEqualTo("KEY_REFUSED");
        assertThat(logs().getFirst().errorMessage()).doesNotContain("sk-secret", "Incorrect API key");
    }

    @Test
    void aRelayThatRejectsItsOwnKeyWithNothingNativeBelowIsNotToldToTheClientAs401() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay);
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(403, error("permission_error", "no"), null, "relay-1", "sk-secret"));

        assertThatThrownBy(() -> responses(codexTurn()))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aRelayThatBreaksOffAnAnswerIsNotChargedAndNothingElseIsAskedForTheSameResponse() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> responses(codexTurn()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // D8: the operator carries it. The reservation is released for good, not left for review, and nothing is charged.
        assertThat(dispatched).containsExactly("relay-1");
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        assertThat(logs().getFirst().stopReason()).isEqualTo("INTERRUPTED");
        assertThat(stateStore.size()).as("an answer that never came is not remembered").isZero();
    }

    @Test
    void theRetryOfAnAnswerARelayBrokeOffGoesToKiroAndTheNextTurnsOfTheChainKeepFromTheRelayForTwoMinutes() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        // A client that names its session by nothing but the chain: the name of the session is the response it continues.
        String first = idOf(responses(plain("start the task", null)));
        assertThat(dispatched).containsExactly("relay-1");
        dispatched.clear();
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));

        assertThatThrownBy(() -> responses(plain("second turn", first))).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        dispatched.clear();

        // The SDK retries the 502 with the same request: the relay, which may still be generating, is not asked.
        String second = idOf(responses(plain("second turn", first)));
        assertThat(dispatched).containsExactly("kiro-1");

        // The next turn continues the response of the retry, which is another session by name; the relay is kept from it all
        // the same, for what is left of the two minutes.
        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(100).toNanos());
        String third = idOf(responses(plain("third turn", second)));
        assertThat(dispatched).containsExactly("kiro-1");

        // Two minutes after the interruption the relay is the chain's again, though the chain's name changed twice; the time
        // did not start again with each hand-over.
        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(21).toNanos());
        responses(plain("fourth turn", third));
        assertThat(dispatched).containsExactly("relay-1");
    }

    /**
     * A relay that was left out of a turn for its features is not asked for the exclusion, which comes last in its checks, and
     * the turn that follows must still be kept from it: the exclusion is handed on whatever the relay did this turn.
     */
    @Test
    void theExclusionOfARelayIsHandedOnThroughATurnTheRelaySatOutForTheFeaturesOfTheRequest() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        String first = idOf(responses(plain("start the task", null)));
        assertThat(dispatched).containsExactly("relay-1");
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));
        assertThatThrownBy(() -> responses(plain("second turn", first))).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        // The client's retry goes to Kiro, and the exclusion goes with the response it produced.
        String second = idOf(responses(plain("second turn", first)));
        dispatched.clear();

        // The third turn declares a tool the relay cannot stand for: it sits the turn out for that, and is not asked
        // whether it is excluded.
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        ObjectNode third = plain("third turn", second);
        third.putArray("tools").addObject().put("type", "custom").put("name", "shell");
        String thirdId = idOf(responses(third));
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(planned.getLast().entryFacts().detected().features()).extracting(feature -> feature.label()).contains("custom_tool");

        // Forty seconds after the interruption the fourth turn is a plain one, which the relay could serve, but it may be
        // generating the answer it broke off still: it is kept out until the two minutes are over.
        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        String fourth = idOf(responses(plain("fourth turn", thirdId)));
        assertThat(dispatched).containsExactly("kiro-1");

        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(81).toNanos());
        responses(plain("fifth turn", fourth));
        assertThat(dispatched).containsExactly("relay-1");
    }

    /**
     * A turn for a model the relay has no mapping for leaves it out for good, which says nothing of the request: the
     * exclusion of the chain is still the chain's when the turns after it ask for the model the relay serves.
     */
    @Test
    void theExclusionOfARelayHoldsThroughATurnForAModelTheRelayHasNoMappingFor() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        String first = idOf(responses(plain("start the task", null)));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));
        assertThatThrownBy(() -> responses(plain("second turn", first))).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        String second = idOf(responses(plain("second turn", first)));
        dispatched.clear();

        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        String third = idOf(responses(plain("claude-opus-4.5", "a question for another model", second)));
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(planned.getLast().upstreamModel()).isEqualTo("claude-opus-4.5");

        // Forty seconds after the interruption the first model is asked for again; the relay may be generating still.
        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        String fourth = idOf(responses(plain("claude-sonnet-4.5", "back to the first model", third)));
        assertThat(dispatched).containsExactly("kiro-1");

        dispatched.clear();
        clock.addAndGet(Duration.ofSeconds(81).toNanos());
        responses(plain("claude-sonnet-4.5", "much later", fourth));
        assertThat(dispatched).containsExactly("relay-1");
    }

    /** Neither the tier binding nor the exclusion of a session is the account affinity's: the chain holds without it. */
    @Test
    void theExclusionOfARelayHoldsThroughTheChainWhenTheVendorKeepsNoSessionsInATier() {
        configure(ownerWithoutStickySessions(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        String first = idOf(responses(plain("start the task", null)));
        fails(relayAnswers, "relay-1", new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent"));
        assertThatThrownBy(() -> responses(plain("second turn", first))).isInstanceOf(ApiException.class);
        relayAnswers.clear();
        String second = idOf(responses(plain("second turn", first)));
        dispatched.clear();

        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        String third = idOf(responses(plain("claude-opus-4.5", "a question for another model", second)));
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        responses(plain("claude-sonnet-4.5", "back to the first model", third));

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
    }

    // ---- the reasoning a relay gives is not carried to an account of OpenAI ----

    /**
     * The reasoning item a relay's answer is given in is the gateway's own rendering: no summary, no encrypted content. The
     * client gets it, but the history the next turn is replayed from does not keep it, or an OpenAI account that is sent it
     * back with {@code store: false} may refuse the request, which no other tier is then asked about.
     */
    @Test
    void theReasoningARelayGaveIsGivenToTheClientButIsNotKeptInTheHistoryTheNextSourceIsReplayedFrom() {
        configure(openAiRelayBesideOfficialKeys());
        ProxyAccountEntity official = account("openai-1", "OpenAI", "api-key", null);
        add(relay, official);
        relayAnswers.put("relay-1", () -> Mono.just(new PlatformCompletionResult("the answer", "thinking it over", List.of(), USAGE,
            StopReason.END_TURN, UsageSource.REPORTED, List.of())));
        ObjectNode first = objectMapper.createObjectNode();
        first.put("model", "gpt-5.4").put("input", "one").put("stream", false);

        JsonNode answer = (JsonNode) facade(offeringOpenAi()).responses(first, exchange(null)).block().getBody();

        assertThat(dispatched).containsExactly("relay-1");
        assertThat(answer.path("output")).extracting(item -> item.path("type").asString()).containsExactly("reasoning", "message");
        // The relay is busy for the next turn of the chain, which falls to the official keys (D2).
        busy.add("relay-1");
        dispatched.clear();
        ObjectNode next = objectMapper.createObjectNode();
        next.put("model", "gpt-5.4").put("input", "two").put("stream", false).put("previous_response_id", answer.path("id").asString());
        // The history itself, which is what any source of the chain is replayed from.
        assertThat(stateStore.expand(next).path("input").toString()).contains("one", "the answer", "two")
            .doesNotContain("thinking it over", "reasoning");
        facade(offeringOpenAi()).responses(next, exchange(null)).block();

        assertThat(dispatched).containsExactly("openai-1");
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiClient).completeResponses(eq(official), sent.capture(), anyString());
        assertThat(sent.getValue().path("input").toString()).contains("one", "the answer", "two")
            .doesNotContain("thinking it over", "reasoning");
    }

    /** A client that sends its whole history every turn sends back the reasoning item it was given. */
    @Test
    void theReasoningItemsARelayGaveAreNotSentToAnAccountOfOpenAiWhenAClientReplaysThem() {
        configure(openAiRelayBesideOfficialKeys());
        ProxyAccountEntity official = account("openai-1", "OpenAI", "api-key", null);
        add(relay, official);
        busy.add("relay-1");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.4").put("stream", false);
        ArrayNode input = request.putArray("input");
        input.addObject().put("type", "message").put("role", "user").put("content", "one");
        // As the gateway renders a relay's reasoning, without its id as Codex sends it back, and one OpenAI gave.
        ObjectNode rendered = input.addObject().put("type", "reasoning");
        rendered.putArray("summary");
        rendered.putArray("content").addObject().put("type", "reasoning_text").put("text", "thinking it over");
        ObjectNode openAis = input.addObject().put("type", "reasoning").put("id", "rs_0123456789abcdef0123456789abcdef0123456789abcdef");
        openAis.putArray("summary").addObject().put("type", "summary_text").put("text", "Looked at it");
        openAis.put("encrypted_content", "gAAAAB");
        input.addObject().put("type", "message").put("role", "assistant").put("content", "the answer");
        input.addObject().put("type", "message").put("role", "user").put("content", "two");

        facade(offeringOpenAi()).responses(request, exchange(null)).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiClient).completeResponses(eq(official), sent.capture(), anyString());
        assertThat(sent.getValue().path("input")).extracting(item -> item.path("type").asString())
            .containsExactly("message", "reasoning", "message", "message");
        assertThat(sent.getValue().path("input").toString()).doesNotContain("thinking it over").contains("gAAAAB", "Looked at it");
        // The request of the client is not changed.
        assertThat(request.path("input")).hasSize(5);
    }

    private static final String PATCH = "*** Begin Patch\n*** Add File: a.txt\n+hi\n*** End Patch";

    /** What {@code XpDispatcher} makes of a relay's call of the editing tool on this entry: the custom tool call Codex expects, the patch for its input. */
    private PlatformCompletionResult patchCall() {
        return new PlatformCompletionResult("", "", List.of(new PlatformToolUse(CALL_ID, "apply_patch",
            objectMapper.createObjectNode().put("patch", PATCH), "custom_tool_call", PATCH, null)), USAGE, StopReason.TOOL_USE,
            UsageSource.REPORTED, List.of());
    }

    private ObjectNode patchRequest(String declaredAs) {
        ObjectNode request = plain("add a file a.txt", null);
        ObjectNode tool = request.putArray("tools").addObject();
        tool.put("type", declaredAs).put("name", "apply_patch").put("description", "apply a patch");
        if ("function".equals(declaredAs)) {
            tool.putObject("parameters").put("type", "object").putObject("properties").putObject("input").put("type", "string");
        }
        return request;
    }

    /**
     * The relay's call of the editing tool is a custom tool call in the chain, and the client answers it with a custom tool call
     * output. When the relay is busy for the next turn, Kiro is asked, whose conversion knew only function calls: the patch and
     * its result were two blank messages, and Kiro answered without knowing the patch was applied while the user paid for the
     * turn. Kiro is given them as the call of the function and its result.
     */
    @Test
    void aPatchTheRelayCalledForACustomToolAndItsResultAreInWhatKiroIsAskedWhenTheNextTurnFallsToKiro() {
        assertThePatchSurvivesTheFallToKiro("custom");
    }

    @Test
    void aPatchTheRelayCalledForAFunctionAndItsResultAreInWhatKiroIsAskedWhenTheNextTurnFallsToKiro() {
        assertThePatchSurvivesTheFallToKiro("function");
    }

    private void assertThePatchSurvivesTheFallToKiro(String declaredAs) {
        relayAnswers.put("relay-1", () -> Mono.just(patchCall()));
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode first = patchRequest(declaredAs);
        JsonNode answer = finalResponse(responses(first));
        assertThat(answer.path("output").path(0).path("type").asString()).isEqualTo("custom_tool_call");
        String callId = answer.path("output").path(0).path("call_id").asString();

        busy.add("relay-1");
        ObjectNode second = plain("ignored", answer.path("id").asString());
        second.set("tools", first.path("tools").deepCopy());
        second.putArray("input").addObject().put("type", "custom_tool_call_output").put("call_id", callId).put("output", "Done!");
        responses(second);

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        JsonNode payload = kiroPayloads.getFirst();
        assertThat(payload.toString()).doesNotContain("\"Continue\"").contains("add a file a.txt").contains("Done!");
        // The patch is in the assistant turn as the call it was, and its result is the result of that call.
        JsonNode history = payload.path("conversationState").path("history");
        JsonNode call = StreamSupport.stream(history.spliterator(), false)
            .map(turn -> turn.path("assistantResponseMessage").path("toolUses"))
            .filter(JsonNode::isArray).flatMap(uses -> StreamSupport.stream(uses.spliterator(), false)).findFirst().orElseThrow();
        assertThat(call.path("name").asString()).isEqualTo("apply_patch");
        assertThat(call.path("toolUseId").asString()).isEqualTo(callId);
        // The argument is the one Kiro was declared the tool with: the `patch` of the function the relays are given when the client
        // declared the tool as a custom tool (D13), the string property of the client's own function when it declared one.
        JsonNode declared = payload.path("conversationState").path("currentMessage").path("userInputMessage").path("userInputMessageContext")
            .path("tools").get(0).path("toolSpecification");
        String argument = "function".equals(declaredAs) ? "input" : "patch";
        assertThat(declared.path("name").asString()).isEqualTo("apply_patch");
        assertThat(declared.path("inputSchema").path("json").path("properties").propertyNames()).containsExactly(argument);
        assertThat(call.path("input").propertyNames()).containsExactly(argument);
        assertThat(call.path("input").path(argument).asString()).as("the patch the client was given, which ends its line").isEqualTo(PATCH + "\n");
        JsonNode results = payload.path("conversationState").path("currentMessage").path("userInputMessage")
            .path("userInputMessageContext").path("toolResults");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("toolUseId").asString()).isEqualTo(callId);
        assertThat(results.get(0).toString()).contains("Done!");
    }

    // ---- the editing tool on the Kiro and Anthropic routes of a vendor that has relays (D13) ----

    /** What Kiro answers when it calls the editing tool: a function call with the argument it was declared the tool with. */
    private PlatformCompletionResult kiroPatchCall(String argument) {
        return new PlatformCompletionResult("", "", List.of(new PlatformToolUse("tooluse_kiro_1", "apply_patch",
            objectMapper.createObjectNode().put(argument, PATCH))), USAGE);
    }

    private static JsonNode declaredToKiro(JsonNode payload) {
        return payload.path("conversationState").path("currentMessage").path("userInputMessage").path("userInputMessageContext")
            .path("tools").get(0).path("toolSpecification");
    }

    /**
     * Codex 0.142.5 applies a patch only as a custom tool call, and aborts the turn on a function call for the editing tool. A relay
     * answers with the custom tool call already; Kiro is declared the editing tool as the function the relays are given, and what it
     * calls it with is told as the custom tool call, so that a turn that falls from the relay to Kiro applies the patch as well.
     */
    @Test
    void kiroIsDeclaredTheEditingToolAsThePatchFunctionAndItsPatchIsToldAsACustomToolCall() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        kiroAnswers.put("kiro-1", () -> Mono.just(kiroPatchCall("patch")));

        ResponseEntity<?> streamed = responses(patchRequest("custom").put("stream", true));
        JsonNode whole = (JsonNode) responses(patchRequest("custom")).getBody();

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        JsonNode declared = declaredToKiro(kiroPayloads.getFirst());
        assertThat(declared.path("name").asString()).isEqualTo("apply_patch");
        assertThat(declared.path("description").asString()).isEqualTo(ApplyPatchEmulation.CHAT_DESCRIPTION);
        assertThat(declared.path("inputSchema").path("json").path("properties").propertyNames()).containsExactly("patch");
        assertThat(declared.path("inputSchema").path("json").path("required")).extracting(JsonNode::asString).containsExactly("patch");
        assertThat(declared.path("inputSchema").path("json").path("properties").path("patch").path("type").asString()).isEqualTo("string");
        for (JsonNode answer : List.of(finalResponse(streamed), whole)) {
            JsonNode call = answer.path("output").get(0);
            assertThat(call.path("type").asString()).isEqualTo("custom_tool_call");
            assertThat(call.path("name").asString()).isEqualTo("apply_patch");
            assertThat(call.path("call_id").asString()).isEqualTo("tooluse_kiro_1");
            assertThat(call.path("input").asString()).as("the patch itself, which ends its line").isEqualTo(PATCH + "\n");
            assertThat(call.has("arguments")).isFalse();
        }
        assertThat(eventTypes(streamed)).contains("response.custom_tool_call_input.done").doesNotContain("response.function_call_arguments.done");
    }

    @Test
    void anAnthropicKeyIsDeclaredTheEditingToolAsThePatchFunctionAndItsPatchIsToldAsACustomToolCall() {
        configure(ownerWithAnthropicKeys(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, anthropicKey("anthropic-1"));
        busy.add("relay-1");
        List<JsonNode> asked = new ArrayList<>();
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            asked.add(invocation.getArgument(1));
            dispatched.add(((ProxyAccountEntity) invocation.getArgument(0)).getAccountId());
            return Mono.just(kiroPatchCall("patch"));
        });

        JsonNode answer = (JsonNode) responses(patchRequest("custom")).getBody();

        assertThat(dispatched).containsExactly("anthropic-1");
        JsonNode declared = asked.getFirst().path("tools").get(0).path("function");
        assertThat(declared.path("name").asString()).isEqualTo("apply_patch");
        assertThat(declared.path("description").asString()).isEqualTo(ApplyPatchEmulation.CHAT_DESCRIPTION);
        assertThat(declared.path("parameters").path("properties").propertyNames()).containsExactly("patch");
        assertThat(declared.path("parameters").path("required")).extracting(JsonNode::asString).containsExactly("patch");
        JsonNode call = answer.path("output").get(0);
        assertThat(call.path("type").asString()).isEqualTo("custom_tool_call");
        assertThat(call.path("input").asString()).isEqualTo(PATCH + "\n");
    }

    /** A tool choice that forces the tool the client declared as a custom one names the function it is declared as to these routes. */
    @Test
    void aToolChoiceThatForcesTheEditingToolNamesTheFunctionTheAnthropicKeyIsDeclared() {
        configure(ownerWithAnthropicKeys(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, anthropicKey("anthropic-1"));
        busy.add("relay-1");
        List<JsonNode> asked = new ArrayList<>();
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            asked.add(invocation.getArgument(1));
            return Mono.just(KIRO_ANSWER);
        });

        responses(patchRequestForcing());

        JsonNode choice = asked.getFirst().path("tool_choice");
        assertThat(choice.path("type").asString()).isEqualTo("function");
        assertThat(choice.path("function").path("name").asString()).isEqualTo("apply_patch");
    }

    private ObjectNode patchRequestForcing() {
        ObjectNode request = patchRequest("custom");
        request.set("tool_choice", objectMapper.readTree("{\"type\":\"custom\",\"name\":\"apply_patch\"}"));
        return request;
    }

    /**
     * The client that declared the tool as a function (Codex's JSON tool) is told the function it declared, and what Kiro calls it
     * with stays a function call, which that tool accepts: only the declaration as a custom tool is turned into the patch function.
     */
    @Test
    void aClientThatDeclaredTheEditingToolAsAFunctionIsDeclaredItAsItWasAndIsToldAFunctionCall() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        busy.add("relay-1");
        kiroAnswers.put("kiro-1", () -> Mono.just(kiroPatchCall("input")));

        JsonNode answer = (JsonNode) responses(patchRequest("function")).getBody();

        JsonNode declared = declaredToKiro(kiroPayloads.getFirst());
        assertThat(declared.path("description").asString()).isEqualTo("apply a patch");
        assertThat(declared.path("inputSchema").path("json").path("properties").propertyNames()).containsExactly("input");
        JsonNode call = answer.path("output").get(0);
        assertThat(call.path("type").asString()).isEqualTo("function_call");
        assertThat(objectMapper.readTree(call.path("arguments").asString()).path("input").asString()).isEqualTo(PATCH);
    }

    /** A vendor whose tiers hold no relay is declared the tool as it always was, with the client's words and no properties, and is told function calls. */
    @Test
    void aVendorWithoutRelaysIsDeclaredTheEditingToolAsItAlwaysWasAndIsToldFunctionCalls() {
        configure(SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 7L)),
            List.of(tier("kiro", 1, "1", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(), List.of(), List.of()));
        add(kiro("kiro-1"));
        kiroAnswers.put("kiro-1", () -> Mono.just(kiroPatchCall("input")));

        JsonNode answer = (JsonNode) responses(patchRequest("custom")).getBody();

        JsonNode declared = declaredToKiro(kiroPayloads.getFirst());
        assertThat(declared.path("description").asString()).isEqualTo("apply a patch");
        assertThat(declared.path("inputSchema").path("json").path("properties").propertyNames()).isEmpty();
        assertThat(answer.path("output").get(0).path("type").asString()).isEqualTo("function_call");
    }

    /**
     * A chain that goes from the relay to Kiro and back applies every patch: each of the calls is a custom tool call in the chain,
     * whoever made it, and the relay is replayed both as the calls of the function it was declared, with their results.
     */
    @Test
    void aChainFromTheRelayToKiroAndBackToTheRelayAppliesEveryPatchAndReplaysEachOfThem() {
        relayAnswers.put("relay-1", () -> Mono.just(patchCall()));
        configure(ownerWithoutStickySessions(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        kiroAnswers.put("kiro-1", () -> Mono.just(kiroPatchCall("patch")));
        ObjectNode first = patchRequest("custom");
        JsonNode one = finalResponse(responses(first));
        assertThat(one.path("output").path(0).path("type").asString()).isEqualTo("custom_tool_call");
        assertThat(one.path("output").path(0).path("call_id").asString()).isEqualTo(CALL_ID);

        // The relay is busy for the second turn: Kiro is replayed the relay's patch with the argument it was declared, and patches too.
        busy.add("relay-1");
        ObjectNode second = plain("ignored", one.path("id").asString());
        second.set("tools", first.path("tools").deepCopy());
        second.putArray("input").addObject().put("type", "custom_tool_call_output").put("call_id", CALL_ID).put("output", "Done!");
        JsonNode two = finalResponse(responses(second));
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        JsonNode replayed = StreamSupport.stream(kiroPayloads.getFirst().path("conversationState").path("history").spliterator(), false)
            .map(turn -> turn.path("assistantResponseMessage").path("toolUses")).filter(JsonNode::isArray)
            .flatMap(uses -> StreamSupport.stream(uses.spliterator(), false)).findFirst().orElseThrow();
        assertThat(replayed.path("input").path("patch").asString()).isEqualTo(PATCH + "\n");
        assertThat(two.path("output").path(0).path("type").asString()).isEqualTo("custom_tool_call");
        assertThat(two.path("output").path(0).path("call_id").asString()).isEqualTo("tooluse_kiro_1");
        assertThat(two.path("output").path(0).path("input").asString()).isEqualTo(PATCH + "\n");

        // The relay is back for the third turn, and is sent both patches, as calls of the function it was declared, with their results.
        busy.clear();
        ObjectNode third = plain("ignored", two.path("id").asString());
        third.set("tools", first.path("tools").deepCopy());
        third.putArray("input").addObject().put("type", "custom_tool_call_output").put("call_id", "tooluse_kiro_1").put("output", "Applied!");
        responses(third);
        assertThat(dispatched).containsExactly("relay-1", "kiro-1", "relay-1");
        JsonNode messages = relayDispatches.getLast().wireBody().path("messages");
        List<JsonNode> calls = StreamSupport.stream(messages.spliterator(), false)
            .filter(message -> message.path("tool_calls").isArray())
            .flatMap(message -> StreamSupport.stream(message.path("tool_calls").spliterator(), false)).toList();
        assertThat(calls).extracting(call -> call.path("id").asString()).containsExactly(CALL_ID, "tooluse_kiro_1");
        assertThat(calls).extracting(call -> call.path("function").path("name").asString()).containsOnly("apply_patch");
        assertThat(calls).extracting(call -> objectMapper.readTree(call.path("function").path("arguments").asString()).path("patch").asString())
            .containsExactly(PATCH + "\n", PATCH + "\n");
        assertThat(StreamSupport.stream(messages.spliterator(), false).filter(message -> "tool".equals(message.path("role").asString()))
            .map(message -> message.path("content").asString())).containsExactly("Done!", "Applied!");
    }

    /** The chain and the client's request are not changed by it, and a vendor without relays is sent what its client sent. */
    @Test
    void aVendorWithoutRelaysIsSentTheCustomToolCallsItsClientReplaysAsItAlwaysWas() {
        configure(SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 7L)),
            List.of(tier("kiro", 1, "1", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(), List.of(), List.of()));
        add(kiro("kiro-1"));
        ObjectNode request = plain("ignored", null);
        request.putArray("input").addObject().put("type", "message").put("role", "user").put("content", "add a file a.txt");
        ((ArrayNode) request.path("input")).addObject().put("type", "custom_tool_call").put("call_id", "call_1")
            .put("name", "apply_patch").put("input", PATCH);
        ((ArrayNode) request.path("input")).addObject().put("type", "custom_tool_call_output").put("call_id", "call_1").put("output", "Done!");

        responses(request);

        // The conversion of this route knows no custom tool calls; nothing is done about them for a vendor that has no relay.
        assertThat(kiroPayloads.getFirst().toString()).doesNotContain("toolUses", "toolResults", PATCH.substring(0, 10));
        assertThat(request.path("input").get(1).path("type").asString()).isEqualTo("custom_tool_call");
    }

    /** A vendor whose tiers hold no relay is sent what its client sent, as it always was. */
    @Test
    void aVendorWithoutRelaysIsSentTheReasoningItsClientReplaysAsItAlwaysWas() {
        configure(SourceTierSnapshot.of(7L, List.of(new VendorConfig("openai", true, false, false, true, 16, true, 7L)),
            List.of(new Tier("openai", "official", 1, "official", BigDecimal.ONE,
                EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null)),
            List.of(), List.of(), List.of()));
        ProxyAccountEntity official = account("openai-1", "OpenAI", "api-key", null);
        add(official);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.4").put("stream", false);
        ArrayNode input = request.putArray("input");
        input.addObject().put("type", "message").put("role", "user").put("content", "one");
        ObjectNode rendered = input.addObject().put("type", "reasoning");
        rendered.putArray("summary");
        rendered.putArray("content").addObject().put("type", "reasoning_text").put("text", "thinking it over");
        input.addObject().put("type", "message").put("role", "user").put("content", "two");

        facade(offeringOpenAi()).responses(request, exchange(null)).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiClient).completeResponses(eq(official), sent.capture(), anyString());
        assertThat(sent.getValue().path("input")).hasSize(3);
        assertThat(sent.getValue().path("input").toString()).contains("thinking it over");
    }

    // ---- the reservation of a relay's attempt (design 7.8, invariant I7) ----

    @Test
    void aClientThatGivesUpWhileTheRelayIsAskedReleasesTheReservationForGood() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", Mono::never);

        Disposable request = facade(offering()).responses(plain("start the task", null), exchange(null)).subscribe();
        assertThat(dispatched).containsExactly("relay-1");
        request.dispose();

        // The relay may be generating the answer and nobody will be charged for it: the operator carries it, and the
        // reservation does not wait for a review.
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), eq("Client disconnected; cross-protocol attempt released"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        assertThat(stateStore.size()).as("an answer that never came is not remembered").isZero();
    }

    @Test
    void aClientThatGivesUpAfterTheRelayAnsweredAndBeforeTheSettlementReturnedReleasesTheReservationForGood() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.never());

        Disposable request = facade(offering()).responses(plain("start the task", null), exchange(null)).subscribe();
        // The answer is in and its settlement is on its way: the attempt is still the relay's until that has returned.
        verify(apiKeyService).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), eq("anthropic:relay"), eq(1), any());
        request.dispose();

        verify(apiKeyService).releaseUsage(any(), anyString(), eq(true), eq("Client disconnected; cross-protocol attempt released"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
    }

    @Test
    void aClientThatGivesUpWhileKiroIsAskedAfterARelayFailedLeavesTheReservationForReviewAsItAlwaysDid() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        fails(relayAnswers, "relay-1", relayErrors.fromResponse(404, error("model_not_found", "x"), null, "relay-1", "sk-secret"));
        kiroAnswers.put("kiro-1", Mono::never);

        Disposable request = facade(offering()).responses(plain("start the task", null), exchange(null)).subscribe();
        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        request.dispose();

        // Kiro has the request now, and what it does with it is not known: a native attempt is not the operator's loss.
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), eq("Client disconnected after dispatch"));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    // ---- what is not changed ----

    @Test
    void aVendorWhoseTiersHaveNoRelayIsNeverReadForRelaysAndIsServedAsBefore() {
        configure(SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
                tier("relay", 1, "1"),
                tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(), List.of(), List.of(gate("RESPONSES", "KEYS", "key-1"))));
        add(kiro("kiro-1"));

        ResponseEntity<?> response = responses(codexTurn());

        assertThat(planned).hasSize(1);
        assertThat(planned.getFirst().entryFacts()).isNull();
        assertThat(planned.getFirst().entry()).isEqualTo(TierEntry.RESPONSES);
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verifyNoInteractions(xpDispatcher);
        String id = finalResponse(response).path("id").asString();
        assertThat(stateStore.ownerAccountId(id)).isEqualTo("kiro-1");
    }

    @Test
    void aGateThatIsOffReadsNothingAndTheRelayIsNeverAsked() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "OFF")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = responses(codexTurn());

        assertThat(planned.getFirst().entryFacts()).isNull();
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aGateOfTheOtherEntryDoesNotOpenTheResponsesEntry() {
        configure(owner(relayMember("relay-1"), gate("CHAT", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responses(codexTurn());

        assertThat(planned.getFirst().entryFacts()).isNull();
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aReleaseThatOffersNoResponsesPairReadsNothingAndServesAsBefore() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        responsesWith(new PairRegistry(List.of(
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic", Status.EXPERIMENTAL),
            new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic", Status.EXPERIMENTAL))), codexTurn());

        assertThat(planned.getFirst().entryFacts()).isNull();
        assertThat(dispatched).containsExactly("kiro-1");
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aFacadeBuiltWithoutTheConverterDoesNotReadTheResponsesEntryForRelays() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = facadeWithoutTheConverter().responses(codexTurn().put("stream", false), exchange(null)).block();

        assertThat(planned.getFirst().entryFacts()).isNull();
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
    }

    @Test
    void aRequestPinnedToAnotherTierIsNotAnsweredByTheRelayAndTheChainIsKirosAsItWas() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode body = codexTurn();
        body.put("model", "claude-sonnet-4.5@kiro");

        ResponseEntity<?> response = responses(body);

        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("pinned");
    }

    @Test
    void aRequestPinnedToTheRelayIsServedByItAndItsResponseHasNoOwnerEither() {
        configure(owner(relayMember("relay-1"), gate("RESPONSES", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode body = codexTurn();
        body.put("model", "claude-sonnet-4.5@relay").put("stream", false);

        ResponseEntity<?> response = responses(body);

        assertThat(dispatched).containsExactly("relay-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("pinned");
        assertThat(stateStore.ownerAccountId(idOf(response))).isNull();
    }

    // ---- fixtures ----

    /** What Codex sends for a Claude model, as the first turn of a session. */
    private ObjectNode codexTurn() {
        return (ObjectNode) objectMapper.readTree(CodexRequests.CLAUDE_TURN);
    }

    /** A plain request: one text, and the response it continues. */
    private ObjectNode plain(String text, String previousResponseId) {
        return plain("claude-sonnet-4.5", text, previousResponseId);
    }

    private ObjectNode plain(String model, String text, String previousResponseId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("input", text).put("stream", false);
        if (previousResponseId != null) {
            body.put("previous_response_id", previousResponseId);
        }
        return body;
    }

    private ResponseEntity<?> responses(ObjectNode body) {
        return responses(body, null);
    }

    private ResponseEntity<?> responses(ObjectNode body, String codexSession) {
        return facade(offering()).responses(body, exchange(codexSession)).block();
    }

    private ResponseEntity<?> responsesWith(PairRegistry pairs, ObjectNode body) {
        return facade(pairs).responses(body, exchange(null)).block();
    }

    private static MockServerWebExchange exchange(String codexSession) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post(RESPONSES_PATH);
        if (codexSession != null) {
            request.header(TierSessionKeys.CODEX_SESSION_HEADER, codexSession);
        }
        return MockServerWebExchange.from(request);
    }

    private static String idOf(ResponseEntity<?> response) {
        return response.getBody() instanceof JsonNode body ? body.path("id").asString() : null;
    }

    /** The response the events of a stream end with, or the body of a response that was not streamed. */
    private JsonNode finalResponse(ResponseEntity<?> response) {
        if (response.getBody() instanceof JsonNode body) {
            return body;
        }
        List<JsonNode> events = events(response);
        JsonNode last = events.stream()
            .filter(event -> event.path("type").asString().startsWith("response.")
                && event.has("response")
                && !event.path("type").asString().equals("response.created"))
            .reduce((first, second) -> second)
            .orElseThrow();
        return last.path("response");
    }

    private List<JsonNode> events(ResponseEntity<?> response) {
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> stream = (Flux<ServerSentEvent<String>>) response.getBody();
        List<JsonNode> parsed = new ArrayList<>();
        for (ServerSentEvent<String> event : stream.collectList().block()) {
            if (event.data() != null && !event.data().equals("[DONE]")) {
                parsed.add(objectMapper.readTree(event.data()));
            }
        }
        return parsed;
    }

    private List<String> eventTypes(ResponseEntity<?> response) {
        List<String> types = new ArrayList<>(events(response).stream().map(event -> event.path("type").asString()).toList());
        types.add("[DONE]");
        return types;
    }

    private static PlatformCompletionResult relayAnswer(String text, StopReason stop) {
        return new PlatformCompletionResult(text, "", List.of(), USAGE, stop, UsageSource.REPORTED, List.of("metadata"));
    }

    private PlatformToolUse execToolCall() {
        return new PlatformToolUse(CALL_ID, "exec_command", objectMapper.createObjectNode().put("cmd", "echo hello"),
            "function_call", "{\"cmd\":\"echo hello\"}", null);
    }

    private PlatformCompletionResult execCall() {
        return new PlatformCompletionResult("", "", List.of(execToolCall()), USAGE, StopReason.TOOL_USE, UsageSource.REPORTED, List.of());
    }

    private static void fails(Map<String, Supplier<Mono<PlatformCompletionResult>>> answers, String accountId, Throwable error) {
        answers.put(accountId, () -> Mono.error(error));
    }

    private String error(String code, String message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("error").put("message", message).put("type", "invalid_request_error").put("code", code);
        return body.toString();
    }

    private List<StatsService.RequestLog> logs() {
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, atLeastOnce()).log(logs.capture());
        return logs.getAllValues();
    }

    private StatsService.RequestLog onlyLog() {
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, times(1)).log(logs.capture());
        return logs.getValue();
    }

    /** The tier the latest reservation says the request started from. */
    private String startedFrom() {
        ArgumentCaptor<String> pricingJson = ArgumentCaptor.forClass(String.class);
        verify(apiKeyService, atLeastOnce()).reserveUsage(any(), anyString(), any(), pricingJson.capture());
        return objectMapper.readTree(pricingJson.getAllValues().getLast()).path("tierPlan").path("startTierRef").asString();
    }

    private void add(ProxyAccountEntity... accounts) {
        cached.addAll(List.of(accounts));
    }

    private void configure(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    /** The pairs this release is taken to offer: the Responses entry of the anthropic vendor, on trial. */
    private static PairRegistry offering() {
        return new PairRegistry(List.of(new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "anthropic", Status.EXPERIMENTAL)));
    }

    private static PairRegistry offeringOpenAi() {
        return new PairRegistry(List.of(new Registration(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai", Status.EXPERIMENTAL)));
    }

    private ProxyFacade facade(PairRegistry pairs) {
        return facade(pairs, new ResponsesChatConverter(objectMapper));
    }

    private ProxyFacade facade(PairRegistry pairs, ResponsesChatConverter converter) {
        ProxyProperties properties = new ProxyProperties();
        properties.setResponsesDefaultOutputTokens(defaultOutputTokens);
        return new ProxyFacade(
            apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings, scheduledTests, modelCatalog,
            modelMapping, rateLimiter, new KiroPayloadFactory(objectMapper, modelCatalog), kiroClient, openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog), new OpenAiResponseFactory(objectMapper), stateStore,
            new AnthropicRequestAdapter(objectMapper, modelCatalog), new AnthropicResponseFactory(objectMapper), anthropicClient,
            new GeminiRequestAdapter(objectMapper), new GeminiResponseFactory(objectMapper), payments, pricing, statsService,
            admissionStore, ownerStore, new TierRuntime(recording(pairs), bindings, learnedLimits), xpDispatcher, converter, properties);
    }

    /** The facade as it is built without the converter of the Responses entry: the constructor the other tests use. */
    private ProxyFacade facadeWithoutTheConverter() {
        return new ProxyFacade(
            apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings, scheduledTests, modelCatalog,
            modelMapping, rateLimiter, new KiroPayloadFactory(objectMapper, modelCatalog), kiroClient, openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog), new OpenAiResponseFactory(objectMapper), stateStore,
            new AnthropicRequestAdapter(objectMapper, modelCatalog), new AnthropicResponseFactory(objectMapper), anthropicClient,
            new GeminiRequestAdapter(objectMapper), new GeminiResponseFactory(objectMapper), payments, pricing, statsService,
            admissionStore, ownerStore, new TierRuntime(recording(offering()), bindings, learnedLimits), xpDispatcher);
    }

    /** A deployment that runs no tiers at all, over the same stores. */
    private ProxyFacade untieredFacade() {
        return new ProxyFacade(
            apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings, scheduledTests, modelCatalog,
            modelMapping, rateLimiter, new KiroPayloadFactory(objectMapper, modelCatalog), kiroClient, openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog), new OpenAiResponseFactory(objectMapper), stateStore,
            new AnthropicRequestAdapter(objectMapper, modelCatalog), new AnthropicResponseFactory(objectMapper), anthropicClient,
            new GeminiRequestAdapter(objectMapper), new GeminiResponseFactory(objectMapper), payments, pricing, statsService,
            admissionStore, ownerStore, TierRuntime.disabled(), xpDispatcher, new ResponsesChatConverter(objectMapper));
    }

    private TierRuntime.Planner recording(PairRegistry pairs) {
        DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog,
            accountPool, payments, pricing, bindings, true, true, pairs, new XpSkipCounts(), learnedLimits);
        return new TierRuntime.Planner() {
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

            @Override
            public boolean tiersInUse() {
                return planner.tiersInUse();
            }
        };
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

    /** The owner's example: the relay as the first tier, Kiro as the second at 0.6. */
    private SourceTierSnapshot owner(Member member, PairGate... gates) {
        return SourceTierSnapshot.of(7L, List.of(vendor()), List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(member), List.of(mapping(member, relay)), List.of(gates));
    }

    /**
     * The owner's example with the Anthropic keys in place of Kiro: the relay as the first tier, the keys as the second at 0.8.
     * The keys serve the Responses entry when the vendor serves every endpoint from the Anthropic route (the all-endpoints switch).
     */
    private SourceTierSnapshot ownerWithAnthropicKeys(Member member, PairGate... gates) {
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, true, false, true, 16, true, 7L)), List.of(
            tier("relay", 1, "1"),
            tier("anthropic-keys", 2, "0.8", AccountAllocationCategory.OFFICIAL_API_KEY)),
            List.of(member), List.of(mapping(member, relay)), List.of(gates));
    }

    private static ProxyAccountEntity anthropicKey(String accountId) {
        return account(accountId, "Anthropic", "api-key", null);
    }

    /** {@link #owner}, for a vendor that does not keep a session in the tier it was pushed down to. */
    private SourceTierSnapshot ownerWithoutStickySessions(Member member, PairGate... gates) {
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig("anthropic", true, false, false, false, 16, true, 7L)), List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(member), List.of(mapping(member, relay)), List.of(gates));
    }

    private static Member relayMember(String accountId) {
        return new Member("anthropic", accountId, "relay", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
    }

    /** A mapping of the model Codex asks for, verified for the account as it is now. */
    private static MemberModel mapping(Member member, ProxyAccountEntity account) {
        return mapping(member, account, 200_000);
    }

    private static MemberModel mapping(Member member, ProxyAccountEntity account, int window) {
        return mapping(member, account, window, null);
    }

    /** The openai vendor with a relay as its first tier, open to key-1 on the Responses entry, and official keys as its second at half the price. */
    private SourceTierSnapshot openAiRelayBesideOfficialKeys() {
        Member member = new Member("openai", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
        MemberModel unverified = new MemberModel("openai", "relay-1", "gpt-5.4", "relay-gpt", 400_000, 8192, true, null, null);
        MemberModel verified = new MemberModel("openai", "relay-1", "gpt-5.4", "relay-gpt", 400_000, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, relay));
        return SourceTierSnapshot.of(7L, List.of(new VendorConfig("openai", true, false, false, true, 16, true, 7L)),
            List.of(new Tier("openai", "relay", 1, "relay", BigDecimal.ONE, EnumSet.noneOf(AccountAllocationCategory.class), true, true, null),
                new Tier("openai", "official", 2, "official", new BigDecimal("0.5"),
                    EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null)),
            List.of(member), List.of(verified),
            List.of(new PairGate("openai", "OPENAI_CHAT", "RESPONSES", "KEYS", List.of("key-1"))));
    }

    /** An official key of a vendor that has no tiers: the accounts of the untiered path are this one. */
    private ProxyAccountEntity untieredOfficial() {
        ProxyAccountEntity official = account("openai-1", "OpenAI", "api-key", null);
        cached.add(official);
        when(accountPool.selectOpenAiApiKeyAccountForPool(any(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(official));
        when(accountPool.selectOpenAiAccountForPool(any(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(official));
        when(accountPool.selectOpenAiApiKeyAccountForPool(any(), any(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(official));
        when(accountPool.selectOpenAiAccountForPool(any(), any(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(official));
        when(accountPool.selectOpenAiAccountByIdForPool(anyString(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(official));
        return official;
    }

    /** The anthropic vendor as in {@link #owner}, and a tiered openai vendor of official keys alone, which keeps sessions in a tier or does not. */
    private SourceTierSnapshot ownerPlusTieredOpenAi(boolean openAiSticky) {
        Member member = relayMember("relay-1");
        return SourceTierSnapshot.of(7L, List.of(vendor(), new VendorConfig("openai", true, false, false, openAiSticky, 16, true, 7L)), List.of(
            tier("relay", 1, "1"),
            tier("kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH),
            new Tier("openai", "official", 1, "official", BigDecimal.ONE, EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null)),
            List.of(member), List.of(mapping(member, relay)), List.of(gate("RESPONSES", "KEYS", "key-1")));
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
