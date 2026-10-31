package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
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
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.xproto.GeminiChatConverter;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.Registration;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.PairRegistry.Status;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.ResponsesChatConverter;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.RequestBounds;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSelectorParser;
import tech.starryapi.proxy.tier.TierSessionBindings;
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
 * The Gemini entry on the tiers (P7-03): a {@code generateContent} request whose plan holds a relay, through the facade, the
 * real planner and the real converter of the Gemini entry, as the owner's example reads for the google vendor (the relay as
 * the first tier, Kiro as the second at 0.6) and for a Claude model asked through the Gemini endpoint. The dispatcher of
 * the relays is a stand-in that answers per account, and so are Kiro and the account pool; the fixtures of the pairs
 * ({@code CrossProtocolContractTest}) play the same entry against a relay over HTTP. What is looked at here is what the
 * fixtures do not say: what the relay is given of the Gemini request, how the pairs decide, what each attempt is charged,
 * and that a deployment without relays meets none of it.
 */
class ProxyFacadeTieredXpGeminiTest {

    private static final String GEMINI_PATH = "/v1beta/models/gemini-2.5-pro:generateContent";
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult KIRO_ANSWER = new PlatformCompletionResult("from kiro", USAGE);
    private static final PlatformCompletionResult RELAY_ANSWER = new PlatformCompletionResult(
        "from the relay", "", List.of(), USAGE, StopReason.END_TURN, UsageSource.REPORTED, List.of("top_k"));
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
    private final TierSessionBindings bindings = new TierSessionBindings(new ProxyProperties(), clock::get);
    private final XpLearnedLimits learnedLimits = new XpLearnedLimits(clock::get);

    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** What each relay answers; a relay that is not listed answers {@link #RELAY_ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> relayAnswers = new HashMap<>();
    /** The accounts that were dispatched to, relays and Kiro alike, in order. */
    private final List<String> dispatched = new ArrayList<>();
    /** The requests the planner made a plan for, as the entries handed them over. */
    private final List<TierPlanRequest> planned = new ArrayList<>();
    private final ProxyAccountEntity relay = account("relay-1", "OpenAI-compatible", "api-key", "https://relay.example/v1");
    private TierDispatch lastDispatch;

    @BeforeEach
    void standInForTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        ApiKeyService.DispatchLease lease = new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenReturn(Mono.just(lease));
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(lease));
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
            Optional<ProxyAccountEntity> found = cached.stream().filter(filter).filter(accountPool::isUsable).findFirst();
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        when(accountPool.hasSelectableAccount(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return Mono.just(cached.stream().anyMatch(filter));
        });
        when(accountPool.refreshCacheIfStale()).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenAnswer(invocation ->
            Mono.just(new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600))));
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            dispatched.add(invocation.<ProxyAccountEntity>getArgument(0).getAccountId());
            return Mono.just(KIRO_ANSWER);
        });
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            dispatched.add(invocation.<ProxyAccountEntity>getArgument(0).getAccountId());
            return Mono.just(KIRO_ANSWER);
        });
        when(xpDispatcher.dispatch(any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            lastDispatch = invocation.getArgument(1);
            dispatched.add(account.getAccountId());
            return relayAnswers.getOrDefault(account.getAccountId(), () -> Mono.just(RELAY_ANSWER)).get();
        });
    }

    // ---- the relay serves ----

    @Test
    void aGeminiRequestIsServedByTheRelayOfTheGoogleVendorAndAnsweredAsAGeminiClientReadsIt() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = gemini("gemini-2.5-pro", geminiBody("hi"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = (JsonNode) response.getBody();
        assertThat(body.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString()).isEqualTo("from the relay");
        assertThat(body.path("modelVersion").asString()).isEqualTo("gemini-2.5-pro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        assertThat(response.getHeaders().toString()).doesNotContain("relay.example", "relay-gemini", "OPENAI_CHAT");
        assertThat(dispatched).containsExactly("relay-1");
        verify(kiroClient, never()).complete(any(), any(), anyString());
        // The request log keeps the Gemini path of the client and says how it was served.
        StatsService.RequestLog row = onlyLog();
        assertThat(row.path()).isEqualTo(GEMINI_PATH);
        assertThat(row.model()).isEqualTo("gemini-2.5-pro");
        assertThat(row.sourceTierRef()).isEqualTo("google:relay");
        assertThat(row.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(row.wireModel()).isEqualTo("relay-gemini");
        assertThat(row.usageSource()).isEqualTo("REPORTED");
        assertThat(row.stopReason()).isEqualTo("END_TURN");
        // 8,000 in and 1,000 out at the full price of the relay's tier, factor 1.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.039")) == 0), eq(8_000L), eq(1_000L), eq("google:relay"), eq(1),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(BigDecimal.ONE) == 0));
    }

    @Test
    void theRelayIsGivenTheConversionOfTheRequestAsTheClientSentItAndNotTheOneKiroIsGiven() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        ObjectNode body = geminiBody("Run two commands");
        body.putArray("tools").addObject().putArray("functionDeclarations").addObject()
            .put("name", "run_shell_command").put("description", "Runs a command");
        // Two calls of one function with no ids (the SDKs that drop the field) and their answers by name, and an image.
        body.withArray("contents").addObject().put("role", "model").putArray("parts").addObject().putObject("functionCall")
            .put("name", "run_shell_command").putObject("args").put("command", "echo first");
        body.withArray("contents").get(1).withArray("parts").addObject().putObject("functionCall")
            .put("name", "run_shell_command").putObject("args").put("command", "echo second");
        var answers = body.withArray("contents").addObject();
        answers.put("role", "user");
        answers.withArray("parts").addObject().putObject("functionResponse").put("name", "run_shell_command")
            .putObject("response").put("output", "first\n");
        answers.withArray("parts").addObject().putObject("functionResponse").put("name", "run_shell_command")
            .putObject("response").put("output", "second\n");
        answers.withArray("parts").addObject().putObject("inlineData").put("mimeType", "image/png").put("data", "iVBORw0KGgo=");

        gemini("gemini-2.5-pro", body);

        JsonNode wire = lastDispatch.wireBody();
        JsonNode messages = wire.path("messages");
        assertThat(messages).extracting(message -> message.path("role").asString())
            .containsExactly("user", "assistant", "tool", "tool", "user");
        // Every call has an id of its own, and each answer is paired with the call it answers (first in, first out).
        JsonNode calls = messages.get(1).path("tool_calls");
        assertThat(calls).extracting(call -> call.path("id").asString()).containsExactly("call_1", "call_2");
        assertThat(messages.get(2).path("tool_call_id").asString()).isEqualTo("call_1");
        assertThat(messages.get(3).path("tool_call_id").asString()).isEqualTo("call_2");
        // The image the native conversion has no place for is in the user's turn, where this member, which has no vision,
        // is given the placeholder the sanitizer writes for it.
        assertThat(messages.get(4).path("content").toString()).contains("image omitted");
        // The limit is the one the request is reserved by, 4,096, which the conversion alone does not name.
        assertThat(wire.path("max_tokens").asInt()).isEqualTo(RequestBounds.FALLBACK_OUTPUT_TOKENS);
    }

    @Test
    void anImageTheMemberSeesIsReservedAtTheCapOfItsMappingAndAnAddressAtTheFloor() {
        configure(owner("google", relayMemberWithVision("google"), gate("google", "GEMINI", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        BigDecimal text = reservedBy(() -> facade().geminiGenerateContent("gemini-2.5-pro", geminiBody("What is this?"),
            exchange(GEMINI_PATH)));

        ObjectNode inline = geminiBody("What is this?");
        inline.withArray("contents").get(0).withArray("parts").addObject().putObject("inlineData")
            .put("mimeType", "image/png").put("data", "iVBORw0KGgo" + "A".repeat(400_000));
        BigDecimal withInline = reservedBy(() -> facade().geminiGenerateContent("gemini-2.5-pro", inline, exchange(GEMINI_PATH)));

        ObjectNode remote = geminiBody("What is this?");
        remote.withArray("contents").get(0).withArray("parts").addObject().putObject("fileData")
            .put("mimeType", "image/jpeg").put("fileUri", "https://example.com/photo.jpg");
        BigDecimal withAddress = reservedBy(() -> facade().geminiGenerateContent("gemini-2.5-pro", remote, exchange(GEMINI_PATH)));

        // 8,192 tokens of image (the cap of the mapping) and not the 400,000 characters of the base64, at the highest of the
        // three input prices ($3.75/M, the cache write): the text is the same in the two requests but for the image part.
        assertThat(withInline.subtract(text)).isBetween(new BigDecimal("0.0300"), new BigDecimal("0.0320"));
        // An image that is only an address has no size to count: the floor of the hidden input of a native account.
        assertThat(withAddress).isGreaterThan(QUOTE.estimate(900_000, RequestBounds.FALLBACK_OUTPUT_TOKENS));
    }

    // ---- the relay cannot serve, and the request goes on to Kiro ----

    @Test
    void aRelayThatDoesNotServeTheModelIsLeftForKiroAndTheRequestIsChargedAtTheSecondTier() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));
        relayAnswers.put("relay-1", () -> Mono.error(
            relayErrors.fromResponse(404, error("model_not_found", "no such model"), null, "relay-1", "sk-secret")));

        ResponseEntity<?> response = gemini("gemini-2.5-pro", geminiBody("hi"));

        assertThat(dispatched).containsExactly("relay-1", "kiro-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("2");
        assertThat(((JsonNode) response.getBody()).path("candidates").path(0).path("content").path("parts").path(0).path("text").asString())
            .isEqualTo("from kiro");
        // Billed at the tier that served: 0.039 at 0.6.
        verify(apiKeyService).accountUsageServed(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
            credits -> credits.compareTo(new BigDecimal("0.0234")) == 0), eq(8_000L), eq(1_000L), eq("google:kiro"), eq(2),
            org.mockito.ArgumentMatchers.argThat(factor -> factor.compareTo(new BigDecimal("0.6")) == 0));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void aClaudeModelAskedThroughTheGeminiEndpointIsServedByTheRelayOfTheAnthropicVendor() {
        configure(owner("anthropic", relayMember("anthropic"), gate("anthropic", "GEMINI", "KEYS", "key-1")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = gemini("claude-sonnet-4.5", geminiBody("hi"));

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(lastDispatch.wireModel()).isEqualTo("relay-claude");
        assertThat(dispatched).containsExactly("relay-1");
    }

    // ---- the relay is not offered the request ----

    @Test
    void aGeminiRequestForAGptModelIsNeverOfferedToARelay() {
        // Nothing asks for a gpt model through the Gemini endpoint (design 7.3): the pair is not offered, whatever the gate says.
        Member member = relayMember("openai");
        configure(SourceTierSnapshot.of(7L, List.of(vendor("openai")), List.of(
            tier("openai", "relay", 1, "1"),
            tier("openai", "official", 2, "0.6", AccountAllocationCategory.OFFICIAL_API_KEY)),
            List.of(member), List.of(mapping(member)), List.of(gate("openai", "GEMINI", "KEYS", "key-1"))));
        add(relay, account("openai-1", "OpenAI", "api-key", null));

        ResponseEntity<?> response = gemini("gpt-5.5", geminiBody("hi"));

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        assertThat(dispatched).containsExactly("openai-1");
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aGeminiRequestWhoseGateIsOpenToAnotherKeyOnlyIsNeverGivenToARelay() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "another-key")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = gemini("gemini-2.5-pro", geminiBody("hi"));

        // The request is read for the entry, since some key could be let through, and the relay is left out of the plan.
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(planned.getFirst().entryFacts()).isNotNull();
        verifyNoInteractions(xpDispatcher);
    }

    // The ways a request names a tier, a key's preference and a caller without a key, with the converter wired, on the entry whose
    // relay is on trial for the keys that are listed: a key that is not on the list is served as if there were no relay.

    @Test
    void aKeyThatIsNotOnTheListIsServedByKiroWhateverItAsksForByHeaderPreferenceOrAnonymously() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "another-key")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> byHeader = facade().geminiGenerateContent("gemini-2.5-pro", geminiBody("hi"),
            MockServerWebExchange.from(MockServerHttpRequest.post(GEMINI_PATH).header(TierSelectorParser.REQUEST_HEADER, "relay"))).block();

        assertThat(byHeader.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(byHeader.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(TierResponseHeaders.IGNORED);
        assertThat(byHeader.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");

        ApiKeyPrincipal preferring = new ApiKeyPrincipal("key-1", "test", null, "test", null, BigDecimal.ZERO, BigDecimal.ZERO, "*",
            false, "BALANCE", "relay");
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(preferring));
        ResponseEntity<?> byPreference = gemini("gemini-2.5-pro", geminiBody("hi"));
        assertThat(byPreference.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");

        ApiKeyPrincipal anonymous = new ApiKeyPrincipal("anonymous", "whale-assistant", "ADMIN", "whale-assistant", null,
            BigDecimal.ZERO, BigDecimal.ZERO, "*");
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(anonymous));
        when(rateLimiter.check("anonymous")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        ResponseEntity<?> unnamed = gemini("gemini-2.5-pro", geminiBody("hi"));
        assertThat(unnamed.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "kiro-1");
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aSuffixThatNamesTheRelayTierIsRefusedForAKeyThatIsNotOnTheListAndServedByTheRelayForOneThatIs() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "another-key")));
        add(relay, kiro("kiro-1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gemini("gemini-2.5-pro@relay", geminiBody("hi")))
            .isInstanceOf(ApiException.class).extracting(failure -> ((ApiException) failure).getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED");
        verifyNoInteractions(xpDispatcher);

        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "KEYS", "key-1")));
        ResponseEntity<?> pinned = gemini("gemini-2.5-pro@relay", geminiBody("hi"));
        assertThat(pinned.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(pinned.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("pinned");
        assertThat(dispatched).containsExactly("relay-1");
    }

    @Test
    void aGateThatIsOnDoesNotLetThroughAKeyThatIsNotListedWhileThePairIsOnlyOnTrial() {
        configure(owner("google", relayMember("google"), gate("google", "GEMINI", "ON")));
        add(relay, kiro("kiro-1"));

        ResponseEntity<?> response = gemini("gemini-2.5-pro", geminiBody("hi"));

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        verifyNoInteractions(xpDispatcher);
    }

    @Test
    void aVendorWithoutTiersNeverMeetsTheConverterOrTheDispatcherOfRelays() {
        configure(SourceTierSnapshot.EMPTY);
        add(kiro("kiro-1"));
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(kiro("kiro-1")));
        GeminiChatConverter converter = mock(GeminiChatConverter.class);

        ResponseEntity<?> response = facadeWith(converter).geminiGenerateContent("gemini-2.5-pro", geminiBody("hi"), exchange(GEMINI_PATH)).block();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(xpDispatcher);
        verifyNoInteractions(converter);
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void tiersWithoutRelaysNeverMeetTheConverterOrTheDispatcherEither() {
        configure(SourceTierSnapshot.of(7L, List.of(vendor("google")), List.of(tier("google", "kiro", 1, "0.6", AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
        add(kiro("kiro-1"));
        GeminiChatConverter converter = mock(GeminiChatConverter.class);

        ResponseEntity<?> response = facadeWith(converter).geminiGenerateContent("gemini-2.5-pro", geminiBody("hi"), exchange(GEMINI_PATH)).block();

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        verifyNoInteractions(xpDispatcher);
        verifyNoInteractions(converter);
        StatsService.RequestLog row = onlyLog();
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
    }

    // ---- fixtures ----

    private ResponseEntity<?> gemini(String model, ObjectNode body) {
        return facade().geminiGenerateContent(model, body, exchange("/v1beta/models/" + model + ":generateContent")).block();
    }

    private ProxyFacade facade() {
        return facadeWith(new GeminiChatConverter(objectMapper));
    }

    private ProxyFacade facadeWith(GeminiChatConverter converter) {
        DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog,
            accountPool, payments, pricing, bindings, true, true, offering(), new XpSkipCounts(), learnedLimits);
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
            apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings, scheduledTests, modelCatalog,
            modelMapping, rateLimiter, new KiroPayloadFactory(objectMapper, modelCatalog), kiroClient, openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog), new OpenAiResponseFactory(objectMapper),
            new OpenAiResponseStateStore(objectMapper), new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper), anthropicClient, new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper), payments, pricing, statsService, admissionStore, ownerStore,
            new TierRuntime(recording, bindings, learnedLimits), xpDispatcher, new ResponsesChatConverter(objectMapper), converter);
    }

    /** What a request reserves, whatever it ends in. */
    private BigDecimal reservedBy(Supplier<Mono<ResponseEntity<?>>> request) {
        request.get().block();
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).reserveUsage(any(), anyString(), credits.capture(), anyString());
        clearInvocations(apiKeyService);
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        return credits.getValue();
    }

    private StatsService.RequestLog onlyLog() {
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService).log(logs.capture());
        return logs.getValue();
    }

    private void add(ProxyAccountEntity... accounts) {
        cached.addAll(List.of(accounts));
    }

    private void configure(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    /** The pairs of this release that the tests are about, on trial; the other vendors' are the registry's own. */
    private static PairRegistry offering() {
        return PairRegistry.defaults();
    }

    private static PairGate gate(String vendor, String entry, String state, String... keys) {
        return new PairGate(vendor, "OPENAI_CHAT", entry, state, List.of(keys));
    }

    private static VendorConfig vendor(String vendor) {
        return new VendorConfig(vendor, true, false, false, true, 16, true, 7L);
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = categories.length == 0
            ? EnumSet.noneOf(AccountAllocationCategory.class)
            : EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), listed, true, true, null);
    }

    /** The owner's example: the relay as the first tier, Kiro as the second at 0.6. */
    private SourceTierSnapshot owner(String vendor, Member member, PairGate... gates) {
        return SourceTierSnapshot.of(7L, List.of(vendor(vendor)), List.of(
            tier(vendor, "relay", 1, "1"),
            tier(vendor, "kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(member), List.of(mapping(member)), List.of(gates));
    }

    private static Member relayMember(String vendor) {
        return new Member(vendor, "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false, true, "NONE", 540, true, true);
    }

    private static Member relayMemberWithVision(String vendor) {
        return new Member(vendor, "relay-1", "relay", "OPENAI_CHAT", true, true, false, false, false, true, "NONE", 540, true, true);
    }

    /** A mapping of the model the tests ask for, verified for the account as it is now. */
    private MemberModel mapping(Member member) {
        String modelId = switch (member.vendor()) {
            case "google" -> "gemini-2.5-pro";
            case "openai" -> "gpt-5.5";
            default -> "claude-sonnet-4.5";
        };
        String wire = switch (member.vendor()) {
            case "google" -> "relay-gemini";
            case "openai" -> "relay-gpt";
            default -> "relay-claude";
        };
        MemberModel unverified = new MemberModel(member.vendor(), member.accountId(), modelId, wire, 1_000_000, 8192, true, null, null);
        return new MemberModel(member.vendor(), member.accountId(), modelId, wire, 1_000_000, 8192, true,
            Instant.parse("2026-10-26T00:00:00Z"), SourceTierValidator.mappingFingerprint(member, unverified, relay));
    }

    private ObjectNode geminiBody(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("contents").addObject().put("role", "user").putArray("parts").addObject().put("text", text);
        return body;
    }

    private String error(String code, String message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("error").put("message", message).put("type", "invalid_request_error").put("code", code);
        return body.toString();
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
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
