package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.ProxyAccountRepository;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingEntity;
import tech.starryapi.model.ModelMappingRepository;
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
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client that names the tier it wants (design 6), end to end through the facade with the real planner, account pool
 * and session bindings: the {@code model@tier} suffix on every endpoint, the {@code X-Starry-Tier} header, the
 * preference of the API key, what is refused before a reservation is made, what is answered when the tier has no
 * account, and that a vendor without tiers (or with tiers switched off) treats a selector as it always treated one.
 *
 * <p>The promise of the other tiered tests holds here too: what a user is charged is the standard price times the factor
 * of the tier that served, and never more than was reserved.
 */
class ProxyFacadeTierPinTest {

    private static final String SESSION_HEADER = "x-claude-code-session-id";
    /** 8,000 tokens in and 1,000 out: 0.039 at the standard price of the quote below, 0.0234 at 0.6 of it. */
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult ANSWER = new PlatformCompletionResult("OK", USAGE);
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
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
    private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    private final AtomicLong clock = new AtomicLong();
    private final ProxyProperties properties = new ProxyProperties();
    private final TierSessionBindings bindings = new TierSessionBindings(properties, clock::get);
    private final Ledger ledger = new Ledger();

    /** The accounts the pool loads. */
    private final List<ProxyAccountEntity> accounts = new ArrayList<>();
    /** The accounts that are switched off: not loaded, but in the table. */
    private final List<ProxyAccountEntity> switchedOff = new ArrayList<>();
    /** The accounts admission refuses as busy. */
    private final Set<String> busy = new HashSet<>();
    /** The accounts the upstream clients were called with, in order. */
    private final List<String> dispatched = new ArrayList<>();
    /** The models the upstream clients were called with, in order. */
    private final List<String> upstreamModels = new ArrayList<>();
    private RoutingPolicySettings routingPolicy = RoutingPolicySettings.defaults();

    private AccountPoolService accountPool;
    private ProxyFacade facade;

    @BeforeEach
    void standInForTheEdges() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            ledger.reserved.add(new Reserved(invocation.getArgument(2), objectMapper.readTree((String) invocation.getArgument(3))));
            return Mono.empty();
        });
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenAnswer(invocation -> {
            ledger.plainDispatches++;
            return Mono.empty();
        });
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenAnswer(invocation -> {
            ledger.leasedTo.add(invocation.getArgument(2));
            return Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59)));
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
            ledger.released.add(invocation.getArgument(2));
            return Mono.empty();
        });
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenAnswer(invocation -> Mono.just(routingPolicy));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
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
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0), invocation.getArgument(2)));
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0), invocation.getArgument(2)));
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0), invocation.getArgument(2)));
        when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0), invocation.getArgument(2)));
    }

    // ---- the suffix names the tier ----

    @Test
    void aSuffixPinsTheRequestToTheTierAndIsReservedAndChargedForThatTierAlone() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = messages("claude-sonnet-4.5@kiro", "session-1");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "pinned", "served");
        // The answer names the model of the catalog, as ever, and not the selector.
        assertThat(((JsonNode) response.getBody()).path("model").asString()).isEqualTo("claude-sonnet-4.5");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        // The reservation is that of the tier named, not the dearest the vendor has.
        assertThat(ledger.holdLevels()).containsExactly("anthropic:kiro");
        assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("anthropic:kiro"));
        assertThat(ledger.reserved.getLast().pricing().path("tierPlan").path("mode").asString()).isEqualTo("pinned");
        assertThat(ledger.leasedTo).containsExactly("anthropic:kiro");
        assertThat(ledger.released).isEmpty();
        assertThat(logged()).singleElement().satisfies(row -> {
            assertThat(row.success()).isTrue();
            assertThat(row.sourceTierRef()).isEqualTo("anthropic:kiro");
            assertThat(row.tierSelection()).isEqualTo("PINNED");
            // The request is logged and priced under the model the client asked for, without the selector.
            assertThat(row.model()).isEqualTo("claude-sonnet-4.5");
        });
        assertThat(ledger.reserved.getLast().pricing().path("model").asString()).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void theUpstreamIsAskedForThePlainModelAndNotForTheSuffix() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        messages("claude-sonnet-4.5@kiro", "session-1");
        messages("claude-sonnet-4.5@L1", "session-1");

        assertThat(dispatched).containsExactly("kiro-1", "official-1");
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5", "claude-sonnet-4.5");
    }

    @Test
    void aClientMayPinTheDearerTierAndIsChargedForIt() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = messages("claude-sonnet-4.5@official", "session-1");

        assertThat(dispatched).containsExactly("official-1");
        assertServedBy(response, "official", "1", "pinned", "served");
        ledger.assertCharged("anthropic:official", 1, "1", "0.039000");
        assertThat(ledger.holdLevels()).containsExactly("anthropic:official");
    }

    @Test
    void aPinnedTierThatIsBusyIsNeverReplacedByAnother() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");

        assertThatThrownBy(() -> messages("claude-sonnet-4.5@official", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE"));

        assertThat(dispatched).isEmpty();
        verify(kiroClient, never()).complete(any(), any(), anyString());
    }

    @Test
    void theTierNamedWithAPlusFallsToTheTiersBelowItAndTheUserPaysForTheOneThatServed() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");

        ResponseEntity<?> response = messages("claude-sonnet-4.5@official+", "session-1");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "pinned", "served");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        // The reservation covered the dearer tier it may have been served from, and the rest went back with the settlement.
        assertThat(ledger.holdLevels()).containsExactly("anthropic:official", "anthropic:kiro");
        assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("anthropic:official"));
        assertThat(ledger.reservedCredits()).isGreaterThan(ledger.settled.getFirst().credits());
    }

    @Test
    void theTierNamedWithAPlusIsServedByTheTierWhenItCanServe() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = messages("claude-sonnet-4.5@L1+", "session-1");

        assertThat(dispatched).containsExactly("official-1");
        assertServedBy(response, "official", "1", "pinned", "served");
        ledger.assertCharged("anthropic:official", 1, "1", "0.039000");
    }

    @Test
    void theChatEndpointTakesTheSuffixToo() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = chat("claude-sonnet-4.5@kiro", null);

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "pinned", "served");
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
    }

    @Test
    void theGeminiPathTakesTheSuffixToo() {
        serve(google(), kiro("kiro-1"), kiro("kiro-2"));

        ResponseEntity<?> response = facade.geminiGenerateContent("gemini-2.5-pro@backup", geminiBody(),
            exchange("/v1beta/models/gemini-2.5-pro@backup:generateContent", Map.of()))
            .block();

        // The accounts of this vendor were put into the two tiers by hand; the one named is the one that served.
        assertThat(dispatched).containsExactly("kiro-2");
        assertServedBy(response, "backup", "2", "pinned", "served");
        assertThat(upstreamModels).containsExactly("gemini-2.5-pro");
        // Everything after the entry sees the plain id: the endpoint the admission scopes its cooldowns by and the one the
        // request log shows have no suffix either.
        assertThat(admittedEndpoints()).containsExactly(GEMINI_PLAIN_ENDPOINT);
        assertThat(logged()).singleElement().satisfies(row -> {
            assertThat(row.path()).isEqualTo(GEMINI_PLAIN_ENDPOINT);
            assertThat(row.model()).isEqualTo("gemini-2.5-pro");
        });
    }

    @Test
    void everySpellingOfASuffixOnTheGeminiPathHasTheEndpointOfThePlainModel() {
        serve(google(), kiro("kiro-1"), kiro("kiro-2"));

        for (String spelling : List.of("backup", "backup+", "2", "L2", "primary+", "auto")) {
            facade.geminiGenerateContent("gemini-2.5-pro@" + spelling, geminiBody(),
                exchange("/v1beta/models/gemini-2.5-pro@" + spelling + ":generateContent", Map.of())).block();
        }
        // A request that names the tier by the header has no suffix to take off, and the same endpoint.
        facade.geminiGenerateContent("gemini-2.5-pro", geminiBody(),
            exchange("/v1beta/models/gemini-2.5-pro:generateContent", Map.of(TierResponseHeaders.TIER, "backup"))).block();

        assertThat(admittedEndpoints()).hasSize(7).containsOnly(GEMINI_PLAIN_ENDPOINT);
        assertThat(logged()).extracting(StatsService.RequestLog::path).hasSize(7).containsOnly(GEMINI_PLAIN_ENDPOINT);
    }

    @Test
    void aGeminiModelWhoseAtIsNotASelectorKeepsTheEndpointItCameWith() {
        // Nobody configured tiers for Google, so the "@backup" stays in the model, as it always did, and in the endpoint.
        serve(anthropic(), kiro("kiro-1"));

        facade.geminiGenerateContent("gemini-2.5-pro@backup", geminiBody(),
            exchange("/v1beta/models/gemini-2.5-pro@backup:generateContent", Map.of())).block();

        assertThat(upstreamModels).containsExactly("gemini-2.5-pro@backup");
        assertThat(admittedEndpoints()).containsExactly("/v1beta/models/gemini-2.5-pro@backup:generateContent");
        assertThat(logged()).singleElement().satisfies(row ->
            assertThat(row.path()).isEqualTo("/v1beta/models/gemini-2.5-pro@backup:generateContent"));
    }

    private static final String GEMINI_PLAIN_ENDPOINT = "/v1beta/models/gemini-2.5-pro:generateContent";

    /** The endpoint scope of every admission the requests asked for, in order. */
    private List<String> admittedEndpoints() {
        ArgumentCaptor<AdmissionRequest> asked = ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(admissionStore, atLeastOnce()).acquire(asked.capture());
        return asked.getAllValues().stream().map(AdmissionRequest::endpointScope).toList();
    }

    @Test
    void aResponsesRequestPinnedToCodexIsServedByCodexAtItsPrice() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));

        ResponseEntity<?> response = responses("gpt-5.4@codex", null, "session-1");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(response, "codex", "2", "pinned", "served");
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
        assertThat(upstreamModels).containsExactly("gpt-5.4");
    }

    // ---- the header names the tier ----

    @Test
    void theHeaderPinsLikeASuffix() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1", "kiro");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "pinned", "served");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
    }

    @Test
    void theSuffixBeatsTheHeaderAndAnAutoSuffixClearsIt() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> pinned = messages("claude-sonnet-4.5@official", "session-1", "kiro");
        ResponseEntity<?> cleared = messages("claude-sonnet-4.5@auto", "session-2", "kiro");

        assertThat(dispatched).containsExactly("official-1", "official-1");
        assertServedBy(pinned, "official", "1", "pinned", "served");
        assertServedBy(cleared, "official", "1", "auto", "served");
    }

    @Test
    void aHeaderThatCannotBeFollowedIsIgnoredAndTheRequestIsServedInTheTiersOrder() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        for (String header : new String[] {"nope", "L9", "internal", "not a tier", "+"}) {
            dispatched.clear();
            ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-" + header, header);

            assertThat(dispatched).as(header).containsExactly("official-1");
            assertServedBy(response, "official", "1", "auto", "ignored");
        }
        // Whatever was ignored was charged as an automatic request is: the first tier's price.
        assertThat(ledger.settled).allSatisfy(charge -> assertThat(charge.tierRef()).isEqualTo("anthropic:official"));
    }

    @Test
    void aHeaderThatCannotBeFollowedStillFallsBackLikeAnyAutomaticRequest() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1", "internal");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto", "ignored");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
    }

    @Test
    void aHeaderForAPoolThatTheEndpointHasNothingInIsIgnoredWhereTheSuffixIsRefused() {
        // What the console writes into an OpenCode configuration for an Anthropic key and the pool "official": OpenCode
        // talks Chat Completions, which sends a Claude model down the Kiro route, where no official key serves.
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = chat("claude-sonnet-4.5", "official");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto", "ignored");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        assertThat(ledger.released).isEmpty();
        // Every request of that configuration is served, not only the first. The same pool by suffix is a demand.
        assertServedBy(chat("claude-sonnet-4.5", "official"), "kiro", "2", "auto", "ignored");
        assertThatThrownBy(() -> chat("claude-sonnet-4.5@official", null))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED"));
    }

    @Test
    void aHeaderForTheCodexPoolIsIgnoredOnTheEndpointsOfficialKeysServeWhenCodexIsIsolated() {
        // Codex tokens and official keys are separate pools by default: Chat Completions reaches the official keys only.
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));

        ResponseEntity<?> response = chat("gpt-5.4", "codex");

        assertThat(dispatched).containsExactly("openai-1");
        assertServedBy(response, "official", "1", "auto", "ignored");
        ledger.assertCharged("openai:official", 1, "1", "0.039000");
    }

    @Test
    void aHeaderThatAResponseChainCannotFollowLetsItsOwnerContinueTheChain() {
        // What the console writes into a Codex configuration for the pool "official": a chain owned by a Codex account
        // cannot leave it, and the header, written for every request of the client, gives way to that.
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        ResponseEntity<?> response = responses("gpt-5.4", "resp_elsewhere", "session-1", "official");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(response, "codex", "2", "auto", "ignored");
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
        // The suffix says it for one request, and is refused.
        assertThatThrownBy(() -> responses("gpt-5.4@official", "resp_elsewhere", "session-2"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("RESPONSE_OWNER_TIER_MISMATCH"));
    }

    // ---- what is refused before a reservation is made (design 6.3) ----

    @Test
    void aSuffixThatNamesNoPoolIsRefusedBeforeAnythingIsReserved() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        for (String model : new String[] {"claude-sonnet-4.5@nope", "claude-sonnet-4.5@L9", "claude-sonnet-4.5@", "claude-sonnet-4.5@a b"}) {
            assertThatThrownBy(() -> messages(model, "session-1"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).as(model).isEqualTo("SOURCE_TIER_UNKNOWN");
                });
        }

        assertNothingReservedOrDispatched();
    }

    @Test
    void aPoolWithNothingThatThisEndpointCanUseIsNotOffered() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        // The Chat endpoint sends a Claude model down the Kiro route, where the official keys do not serve.
        assertThatThrownBy(() -> chat("claude-sonnet-4.5@official", null))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED");
                assertThat(error.getMessage()).isEqualTo("The tier \"official\" has no account that serves this model on this endpoint");
            });
        // The same request through Messages has both routes' accounts.
        assertThat(messages("claude-sonnet-4.5@official", "session-1").getStatusCode().value()).isEqualTo(200);

        assertThat(ledger.reserved).hasSize(1);
    }

    @Test
    void aPoolTheAdministratorKeepsToItselfIsForbidden() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), claudeOauth("oauth-1"));

        for (String model : new String[] {"claude-sonnet-4.5@internal", "claude-sonnet-4.5@L3", "claude-sonnet-4.5@internal+"}) {
            assertThatThrownBy(() -> messages(model, "session-1"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getCode()).as(model).isEqualTo("SOURCE_TIER_NOT_SELECTABLE");
                });
        }

        assertNothingReservedOrDispatched();
    }

    @Test
    void aResponseChainThatCannotLeaveItsOwnerIsNotMovedToAnotherPool() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        assertThatThrownBy(() -> responses("gpt-5.4@official", "resp_elsewhere", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("RESPONSE_OWNER_TIER_MISMATCH");
            });

        assertNothingReservedOrDispatched();
    }

    @Test
    void aResponseChainIsContinuedByItsOwnerWhenTheClientNamesTheOwnersPool() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        ResponseEntity<?> response = responses("gpt-5.4@codex", "resp_elsewhere", "session-1");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(response, "codex", "2", "pinned", "served");
    }

    @Test
    void aPoolWithNoAccountToOfferIsAnsweredWith503AndARetryAfterAndTheReservationGoesBack() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("kiro-1");
        MockServerWebExchange exchange = exchange("/v1/messages", Map.of(SESSION_HEADER, "session-1"));

        assertThatThrownBy(() -> facade.messages(messagesBody("claude-sonnet-4.5@kiro"), exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
                assertThat(error.getMessage()).isEqualTo("The tier you chose has no account available right now");
            });

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        // The reservation was made for the pool and given back whole: nothing was dispatched, nothing is left to review.
        assertThat(ledger.reserved).hasSize(1);
        assertThat(ledger.released).hasSize(1);
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.leasedTo).isEmpty();
        assertThat(dispatched).isEmpty();
    }

    @Test
    void theSameRequestWithoutAPoolAnswersTheOrdinaryNoAvailableAccount() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.addAll(Set.of("official-1", "kiro-1"));

        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));
    }

    // ---- a vendor without tiers (or with tiers switched off) ----

    @Test
    void aVendorNobodyConfiguredTreatsTheSuffixAsPartOfTheModelAndTheHeaderAsNothing() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));

        ResponseEntity<?> response = chat("gpt-5.4@kiro", "kiro");

        // The OpenAI vendor has no tiers: the model id goes to the upstream as it came, as it always did.
        assertThat(upstreamModels).containsExactly("gpt-5.4@kiro");
        assertThat(dispatched).containsExactly("openai-1");
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        assertThat(ledger.plainDispatches).isEqualTo(1);
        assertThat(ledger.leasedTo).isEmpty();
        assertThat(ledger.reserved.getFirst().pricing().has("tierPlan")).isFalse();
        assertThat(ledger.reserved.getFirst().pricing().path("model").asString()).isEqualTo("gpt-5.4@kiro");
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void aVendorWithTiersSwitchedOffDropsTheSuffixAndSaysThatItDid() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> named = messages("claude-sonnet-4.5@kiro", "session-1");
        ResponseEntity<?> byHeader = messages("claude-sonnet-4.5", "session-2", "kiro");
        ResponseEntity<?> plain = messages("claude-sonnet-4.5", "session-3");
        ResponseEntity<?> auto = messages("claude-sonnet-4.5@auto", "session-4");

        // The old, untiered selection: the Anthropic keys first, with the model as the client meant it.
        assertThat(dispatched).containsExactly("official-1", "official-1", "official-1", "official-1");
        assertThat(upstreamModels).containsOnly("claude-sonnet-4.5");
        assertThat(named.getHeaders().headerNames()).filteredOn(name -> name.toLowerCase().startsWith("x-starry-"))
            .containsExactly(TierResponseHeaders.STATUS);
        assertThat(named.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("ignored");
        assertThat(byHeader.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("ignored");
        // A request that named nothing, or only the automatic choice, has nothing to be told about.
        assertThat(plain.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        assertThat(auto.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        assertThat(ledger.plainDispatches).isEqualTo(4);
        assertThat(ledger.leasedTo).isEmpty();
        assertThat(ledger.settled).allSatisfy(charge -> assertThat(charge.tierRef()).isNull());
        assertThat(ledger.reserved).allSatisfy(reserved -> assertThat(reserved.pricing().has("tierPlan")).isFalse());
    }

    @Test
    void aSwitchedOffVendorDoesNotRefuseWhatATieredOneWould() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"));

        // Neither a pool that does not exist nor one nobody may choose means anything where tiers are off.
        assertThat(messages("claude-sonnet-4.5@nope", "session-1").getStatusCode().value()).isEqualTo(200);
        assertThat(messages("claude-sonnet-4.5@internal", "session-1").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anIdWhoseAtNamesNoPoolKeepsItEvenWhereTiersAreOn() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        // Not a catalog model, and an @ that is not the name of a pool of the vendor that serves it: the id means what it meant.
        messages("my-own-model@20250514", "session-1");
        messages("claude-opus-9-9@20260115", "session-2");

        assertThat(upstreamModels).containsExactly("my-own-model@20250514", "claude-opus-9-9@20260115");
    }

    @Test
    void aModelTheCatalogDoesNotListIsPinnedBySuffixWhenTheVendorThatServesItHasThePool() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        // A Claude id newer than the catalog is served by the Anthropic keys and Kiro all the same.
        ResponseEntity<?> named = messages("claude-opus-4-8-20260115@kiro", "session-1");
        ResponseEntity<?> plain = messages("claude-opus-4-8-20260115", "session-2");

        assertServedBy(named, "kiro", "2", "pinned", "served");
        assertServedBy(plain, "official", "1", "auto", "served");
        assertThat(dispatched).containsExactly("kiro-1", "official-1");
        assertThat(upstreamModels).containsExactly("claude-opus-4-8-20260115", "claude-opus-4-8-20260115");
        assertThat(logged()).extracting(StatsService.RequestLog::model).containsOnly("claude-opus-4-8-20260115");
        assertThat(ledger.reserved.getFirst().pricing().path("model").asString()).isEqualTo("claude-opus-4-8-20260115");
    }

    @Test
    void aCodexModelIsPinnedBySuffixLikeAnyOther() {
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));

        // The price repository lists no Codex model, and Codex serves them.
        ResponseEntity<?> response = responses("gpt-5.1-codex-max@codex", null, "session-1");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(response, "codex", "2", "pinned", "served");
        assertThat(upstreamModels).containsExactly("gpt-5.1-codex-max");
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
        assertThat(logged()).singleElement().satisfies(row -> assertThat(row.model()).isEqualTo("gpt-5.1-codex-max"));
        // Only Codex accounts serve it on this route, so the other pool has nothing to offer.
        assertThatThrownBy(() -> responses("gpt-5.1-codex-max@official", null, "session-2"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED"));
    }

    @Test
    void anIdTheKeyMapsAsItStandsIsMappedAndNotReadAsASelector() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        // A Vertex-style id that clients of the key use for Claude Sonnet 4.
        when(modelMapping.hasAtSource("claude-sonnet-4@20250514", "key-1")).thenReturn(true);
        when(modelMapping.apply("claude-sonnet-4@20250514", "key-1")).thenReturn("claude-sonnet-4.5");

        ResponseEntity<?> response = messages("claude-sonnet-4@20250514", "session-1");

        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
        assertServedBy(response, "official", "1", "auto", "served");
        // Without that rule the same text is a pool that does not exist.
        when(modelMapping.hasAtSource("claude-sonnet-4@20250514", "key-1")).thenReturn(false);
        assertThatThrownBy(() -> messages("claude-sonnet-4@20250514", "session-2"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNKNOWN"));
    }

    @Test
    void anIdTheKeyMapsAsItStandsIsMappedWhereTheVendorIsSwitchedOffToo() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"));
        when(modelMapping.hasAtSource("claude-sonnet-4@20250514", "key-1")).thenReturn(true);
        when(modelMapping.apply("claude-sonnet-4@20250514", "key-1")).thenReturn("claude-sonnet-4.5");

        ResponseEntity<?> response = messages("claude-sonnet-4@20250514", "session-1");

        // The model of the rule's target, not the model in front of the @, and no word about tiers.
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
    }

    @Test
    void aModelTheKeyMapsToAVendorNobodyConfiguredKeepsItsAtAsBeforeTiers() {
        serve(anthropic(), kiro("kiro-1"), officialOpenAi("openai-1"));
        when(modelMapping.apply("claude-sonnet-4.5", "key-1")).thenReturn("gpt-5.4");

        // The mapping does not apply to an id that ends in an @, as it never did; and the vendor that the mapping sends
        // the model to has no tiers, so the @ is no selector there either, known or not.
        ResponseEntity<?> named = chat("claude-sonnet-4.5@kiro", null);
        ResponseEntity<?> unknown = chat("claude-sonnet-4.5@nope", null);

        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5@kiro", "claude-sonnet-4.5@nope");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        // What serves the id is a Kiro account, which the tiers of the anthropic vendor hold: the request is served in
        // their order, and neither the pool it wrote nor one it did not is taken for a selector.
        for (ResponseEntity<?> response : List.of(named, unknown)) {
            assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
            assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        }
    }

    // ---- the preference of the key ----

    @Test
    void aKeyThatPrefersAPoolIsServedFromItLikeARequestThatNamedIt() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "kiro");

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "pinned", "served");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        assertThat(ledger.holdLevels()).containsExactly("anthropic:kiro");
        assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("anthropic:kiro"));
        assertThat(ledger.reserved.getLast().pricing().path("tierPlan").path("mode").asString()).isEqualTo("pinned");
        assertThat(ledger.released).isEmpty();
        assertThat(logged()).singleElement().satisfies(row -> {
            assertThat(row.sourceTierRef()).isEqualTo("anthropic:kiro");
            assertThat(row.tierSelection()).isEqualTo("PINNED");
        });
    }

    @Test
    void thePreferenceIsWrittenLikeASuffixWithAPlusAndByLevel() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        for (String preference : new String[] {"kiro", "Kiro", "l2", "L2", "2"}) {
            dispatched.clear();
            authenticateAs("Anthropic", preference);
            messages("claude-sonnet-4.5", "session-" + preference);
            assertThat(dispatched).as(preference).containsExactly("kiro-1");
        }
        for (String preference : new String[] {"official+", "l1+", "1+"}) {
            dispatched.clear();
            authenticateAs("Anthropic", preference);
            messages("claude-sonnet-4.5", "session-" + preference);
            assertThat(dispatched).as(preference).containsExactly("official-1");
        }
    }

    @Test
    void aPreferenceWithAPlusFallsToTheTiersBelowAndTheUserPaysForTheOneThatServed() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "official+");
        busy.add("official-1");

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "pinned", "served");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        assertThat(ledger.holdLevels()).containsExactly("anthropic:official", "anthropic:kiro");
    }

    @Test
    void aPreferenceForAPoolWithNoAccountToOfferIsAnsweredWith503LikeASuffix() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "kiro");
        busy.add("kiro-1");

        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE"));

        assertThat(dispatched).isEmpty();
        assertThat(ledger.released).hasSize(1);
        assertThat(ledger.settled).isEmpty();
    }

    @Test
    void theSuffixBeatsThePreferenceAndTheHeaderBeatsItToo() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "kiro");

        ResponseEntity<?> bySuffix = messages("claude-sonnet-4.5@official", "session-1");
        ResponseEntity<?> byHeader = messages("claude-sonnet-4.5", "session-2", "official");

        assertThat(dispatched).containsExactly("official-1", "official-1");
        assertServedBy(bySuffix, "official", "1", "pinned", "served");
        assertServedBy(byHeader, "official", "1", "pinned", "served");
    }

    @Test
    void anAutoSuffixOrAnAutoHeaderTakesThePreferenceBack() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "official");
        busy.add("official-1");

        // The preference is strict: the busy first pool is not replaced. Asking for auto serves the request in the tiers' order.
        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE"));
        ResponseEntity<?> bySuffix = messages("claude-sonnet-4.5@auto", "session-2");
        ResponseEntity<?> byHeader = messages("claude-sonnet-4.5", "session-3", "auto");

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertServedBy(bySuffix, "kiro", "2", "auto", "served");
        assertServedBy(byHeader, "kiro", "2", "auto", "served");
    }

    @Test
    void aPreferenceThatNamesNoPoolOrOneNobodyMayChooseIsIgnoredAndTheResponseSaysSo() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), claudeOauth("oauth-1"));

        for (String preference : new String[] {"nope", "l9", "internal", "l3", "internal+"}) {
            dispatched.clear();
            authenticateAs("Anthropic", preference);
            ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-" + preference);

            assertThat(dispatched).as(preference).containsExactly("official-1");
            assertServedBy(response, "official", "1", "auto", "ignored");
        }
        // Whatever was ignored was charged as an automatic request is: the first tier's price.
        assertThat(ledger.settled).allSatisfy(charge -> assertThat(charge.tierRef()).isEqualTo("anthropic:official"));
        assertThat(ledger.released).isEmpty();
    }

    @Test
    void aPreferenceThatIsIgnoredStillFallsBackLikeAnyAutomaticRequest() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "nope");
        busy.add("official-1");

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto", "ignored");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
    }

    @Test
    void aPreferenceForAPoolThatThisEndpointHasNothingInGivesWay() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "official");

        // The Chat endpoint sends a Claude model down the Kiro route, where the official keys do not serve.
        ResponseEntity<?> response = chat("claude-sonnet-4.5", null);
        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto", "ignored");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        // The same key through Messages, which has both routes' accounts, follows its preference.
        assertServedBy(messages("claude-sonnet-4.5", "session-1"), "official", "1", "pinned", "served");
        // A suffix on the Chat endpoint is a demand of the client and is refused.
        assertThatThrownBy(() -> chat("claude-sonnet-4.5@official", null))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED"));
    }

    @Test
    void aResponseChainThatCannotLeaveItsOwnerIsContinuedByItAgainstAKeyPreference() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        authenticateAs("OpenAI", "official");
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        ResponseEntity<?> response = responses("gpt-5.4", "resp_elsewhere", "session-1");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(response, "codex", "2", "auto", "ignored");
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
    }

    @Test
    void aKeyThatIsNotTiedToOneProviderHasNoPreference() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        for (String provider : new String[] {"*", "", " ", null}) {
            dispatched.clear();
            authenticateAs(provider, "kiro");
            ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-" + provider);

            assertThat(dispatched).as("provider " + provider).containsExactly("official-1");
            // Not even a word that the preference was left out: such a key can only be served in the tiers' order.
            assertServedBy(response, "official", "1", "auto", "served");
        }
    }

    @Test
    void aKeyWithoutAPreferenceIsServedInTheTiersOrderAsEver() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        for (String preference : new String[] {"auto", "AUTO", " ", null}) {
            dispatched.clear();
            authenticateAs("Anthropic", preference);
            ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-" + preference);

            assertThat(dispatched).as("preference " + preference).containsExactly("official-1");
            assertServedBy(response, "official", "1", "auto", "served");
        }
    }

    @Test
    void aPreferenceIsNothingToAVendorNobodyConfigured() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));
        authenticateAs("OpenAI", "kiro");

        ResponseEntity<?> response = chat("gpt-5.4", null);

        // The OpenAI vendor has no tiers: the request is the one it always was, with no word about tiers.
        assertThat(dispatched).containsExactly("openai-1");
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        assertThat(ledger.plainDispatches).isEqualTo(1);
        assertThat(ledger.leasedTo).isEmpty();
        assertThat(ledger.reserved.getFirst().pricing().has("tierPlan")).isFalse();
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void aPreferenceForAVendorWithTiersSwitchedOffIsIgnoredAndTheResponseSaysSo() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "kiro");

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1");

        assertThat(dispatched).containsExactly("official-1");
        assertThat(response.getHeaders().headerNames()).filteredOn(name -> name.toLowerCase().startsWith("x-starry-"))
            .containsExactly(TierResponseHeaders.STATUS);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("ignored");
        assertThat(ledger.plainDispatches).isEqualTo(1);
        assertThat(ledger.settled).allSatisfy(charge -> assertThat(charge.tierRef()).isNull());
    }

    @Test
    void aPreferenceNeitherReadsNorMovesTheTierOfTheSession() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");
        messages("claude-sonnet-4.5", "session-1");
        assertThat(dispatched).containsExactly("kiro-1");

        // The session has fallen to Kiro and stays there for an automatic request, but a key that prefers a pool starts from it.
        busy.clear();
        authenticateAs("Anthropic", "official");
        assertServedBy(messages("claude-sonnet-4.5", "session-1"), "official", "1", "pinned", "served");

        authenticateAs("Anthropic", "auto");
        dispatched.clear();
        messages("claude-sonnet-4.5", "session-1");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aRequestRefusedForTheBalanceOfAKeyThatPrefersAPoolGetsNoAdviceToPinOneAndAnIgnoredPreferenceDoes() {
        serve(anthropic(true), officialKey("official-1"), kiro("kiro-1"));
        refuseReservationsForTheBalance();

        authenticateAs("Anthropic", "official");
        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getMessage()).isEqualTo("余额不足以支付本次请求的预估费用"));
        authenticateAs("Anthropic", "nope");
        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-2"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getMessage())
                .contains("claude-sonnet-4.5@kiro").contains("API Key"));
    }

    // ---- the anonymous caller ----

    @Test
    void theAnonymousCallerCannotNameATier() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5").put("max_tokens", 100);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");

        // The header names a pool the Kiro route does not have; honoured it would be refused, ignored it is served.
        String answer = facade.completeAnonymousChat(body, exchange("/v1/chat/completions", Map.of("X-Starry-Tier", "official"))).block();

        assertThat(answer).isEqualTo("OK");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void theAnonymousCallerIsServedInTheTiersOrderAndToldThatWhatItNamedWasNotUsed() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAnonymously();

        ResponseEntity<?> byHeader = messages("claude-sonnet-4.5", "session-1", "kiro");
        ResponseEntity<?> bySuffix = messages("claude-sonnet-4.5@kiro", "session-2");
        ResponseEntity<?> byBoth = messages("claude-sonnet-4.5@L2", "session-3", "kiro");
        ResponseEntity<?> plain = messages("claude-sonnet-4.5", "session-4");
        ResponseEntity<?> auto = messages("claude-sonnet-4.5@auto", "session-5");

        // Never pinned, and the upstream is asked for the plain model: the suffix is not left on the id.
        assertThat(dispatched).containsExactly("official-1", "official-1", "official-1", "official-1", "official-1");
        assertThat(upstreamModels).containsOnly("claude-sonnet-4.5");
        assertServedBy(byHeader, "official", "1", "auto", "ignored");
        assertServedBy(bySuffix, "official", "1", "auto", "ignored");
        assertServedBy(byBoth, "official", "1", "auto", "ignored");
        assertServedBy(plain, "official", "1", "auto", "served");
        assertServedBy(auto, "official", "1", "auto", "served");
        // Nobody is charged, so nothing is reserved either.
        assertThat(ledger.reserved).isEmpty();
    }

    @Test
    void theAnonymousCallerOfAVendorWithTiersSwitchedOffOrNeverConfiguredIsTreatedAsAnyoneIs() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));
        authenticateAnonymously();

        ResponseEntity<?> switchedOff = messages("claude-sonnet-4.5@kiro", "session-1");
        ResponseEntity<?> neverConfigured = chat("gpt-5.4@kiro", null);

        // A vendor whose tiers are off drops the suffix and says so; one nobody configured keeps the id as it is.
        assertThat(switchedOff.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("ignored");
        assertThat(neverConfigured.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5", "gpt-5.4@kiro");
    }

    // ---- a pinned request leaves the session where it is (design 4.7) ----

    @Test
    void aPinnedRequestNeitherReadsNorMovesTheTierOfTheSession() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");
        messages("claude-sonnet-4.5", "session-1");
        assertThat(dispatched).containsExactly("kiro-1");

        // The session has fallen to Kiro and stays there for an automatic request, but a pinned one starts from the pool it names.
        busy.clear();
        ResponseEntity<?> pinned = messages("claude-sonnet-4.5@official", "session-1");
        assertServedBy(pinned, "official", "1", "pinned", "served");

        dispatched.clear();
        messages("claude-sonnet-4.5", "session-1");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aPinnedRequestDoesNotPushTheSessionDownEither() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");

        // Pinned to "official or below", the busy first pool sends it to Kiro: that is the request's own business.
        messages("claude-sonnet-4.5@official+", "session-1");
        busy.clear();
        dispatched.clear();
        messages("claude-sonnet-4.5", "session-1");

        assertThat(dispatched).containsExactly("official-1");
    }

    @Test
    void aPinnedTurnOfAResponsesChainDoesNotMoveTheLaterAutomaticTurnsOfASessionThatFellToCodex() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        // The official key is busy with other load: the session falls to Codex (x0.5) and stays there (design 4.7).
        busy.add("openai-1");
        String first = responseId(responses("gpt-5.4", null, "s1"));
        busy.clear();
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");

        // One turn pinned to the official key: it is served there and is the owner of the chain from then on ...
        ledger.settled.clear();
        ResponseEntity<?> pinned = responses("gpt-5.4@official", first, "s1");
        assertServedBy(pinned, "official", "1", "pinned", "served");
        ledger.assertCharged("openai:official", 1, "1", "0.039000");

        // ... but the session is still in Codex, so the automatic turns after it are too, and pay Codex's price.
        String previous = responseId(pinned);
        for (int turn = 0; turn < 2; turn++) {
            ledger.settled.clear();
            dispatched.clear();
            ResponseEntity<?> automatic = responses("gpt-5.4", previous, "s1");
            assertServedBy(automatic, "codex", "2", "auto", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("codex-1");
            assertThat(ledger.holdLevels()).containsExactly("openai:codex");
            assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("openai:codex"));
            ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
            previous = responseId(automatic);
        }
        // Without the chain it is the same session, in the same place.
        ledger.settled.clear();
        assertServedBy(responses("gpt-5.4", null, "s1"), "codex", "2", "auto", "served");
    }

    @Test
    void aPinnedTurnOfAChainDoesNotKeepTheLaterAutomaticTurnsOfASessionThatNeverFellInThePinnedTier() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiDearerCodex(), officialOpenAi("openai-1"), codex("codex-1"));
        ResponseEntity<?> first = responses("gpt-5.4", null, "s1");
        assertServedBy(first, "official", "1", "auto", "served");

        // One turn pinned to the dearer Codex pool: it is served there and Codex owns the chain from then on ...
        ledger.settled.clear();
        ResponseEntity<?> pinned = responses("gpt-5.4@codex", responseId(first), "s1");
        assertServedBy(pinned, "codex", "2", "pinned", "served");
        ledger.assertCharged("openai:codex", 2, "2", "0.078000");

        // ... but the session was never pushed down, so the automatic turns after it start from the best pool, are served
        // and priced by it, and Codex's owner is not what they are tried against.
        String previous = responseId(pinned);
        for (int turn = 0; turn < 3; turn++) {
            ledger.settled.clear();
            dispatched.clear();
            ResponseEntity<?> automatic = responses("gpt-5.4", previous, "s1");
            assertServedBy(automatic, "official", "1", "auto", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("openai-1");
            ledger.assertCharged("openai:official", 1, "1", "0.039000");
            previous = responseId(automatic);
        }
    }

    @Test
    void aPinnedTurnBelowThePoolASessionFellToDoesNotPullTheLaterAutomaticTurnsDownToIt() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiThreeTiers(), officialOpenAi("openai-1"), codex("codex-1"), officialOpenAi("openai-2"));
        // The session falls to Codex (x0.5) for real and stays there.
        busy.add("openai-1");
        ResponseEntity<?> first = responses("gpt-5.4", null, "s1");
        assertServedBy(first, "codex", "2", "auto", "served");
        busy.clear();

        // A turn pinned to the dearer pool below Codex (x3) owns the chain from then on.
        ledger.settled.clear();
        ResponseEntity<?> pinned = responses("gpt-5.4@spare", responseId(first), "s1");
        assertServedBy(pinned, "spare", "3", "pinned", "served");
        ledger.assertCharged("openai:spare", 3, "3", "0.117000");

        // The turns after it start in Codex, where the session is, and pay Codex's price.
        String previous = responseId(pinned);
        for (int turn = 0; turn < 3; turn++) {
            ledger.settled.clear();
            dispatched.clear();
            ResponseEntity<?> automatic = responses("gpt-5.4", previous, "s1");
            assertServedBy(automatic, "codex", "2", "auto", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("codex-1");
            ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
            previous = responseId(automatic);
        }
        // Without the chain it is the same session, in the same place.
        ledger.settled.clear();
        assertServedBy(responses("gpt-5.4", null, "s1"), "codex", "2", "auto", "served");
    }

    @Test
    void aChainOfASessionThatFellGoesBackToTheBestPoolWhenTheSessionHasBeenIdleForLongerThanItsBindingLives() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiDearerCodex(), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");
        ResponseEntity<?> first = responses("gpt-5.4", null, "s1");
        assertServedBy(first, "codex", "2", "auto", "served");
        busy.clear();

        // Within the ten minutes the session stays in Codex (x2), also for the chain whose owner Codex is.
        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        ledger.settled.clear();
        ResponseEntity<?> stays = responses("gpt-5.4", responseId(first), "s1");
        assertServedBy(stays, "codex", "2", "auto", "served");
        ledger.assertCharged("openai:codex", 2, "2", "0.078000");

        // Idle for longer than the binding lives, it is a new session: the chain starts from the best pool again, although
        // the best pool is the cheaper one here and the owner of the chain is in the dearer.
        clock.addAndGet(Duration.ofMinutes(11).toNanos());
        ledger.settled.clear();
        dispatched.clear();
        ResponseEntity<?> afterIdle = responses("gpt-5.4", responseId(stays), "s1");
        assertServedBy(afterIdle, "official", "1", "auto", "served");
        assertThat(dispatched).containsExactly("openai-1");
        ledger.assertCharged("openai:official", 1, "1", "0.039000");
        // The same session without a chain does the same.
        clock.addAndGet(Duration.ofMinutes(11).toNanos());
        dispatched.clear();
        responses("gpt-5.4", null, "s1");
        assertThat(dispatched).containsExactly("openai-1");
    }

    @Test
    void aPlusPinnedChainGoesBackToTheNamedPoolOnceItIsFreeAfterAFallInsideThePlusRange() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiDearerCodex(), officialOpenAi("openai-1"), codex("codex-1"));
        // The named pool is busy: "official or below" falls to the dearer Codex pool (x2), for this turn ...
        busy.add("openai-1");
        ResponseEntity<?> first = responses("gpt-5.4@official+", null, "s1");
        assertServedBy(first, "codex", "2", "pinned", "served");
        ledger.assertCharged("openai:codex", 2, "2", "0.078000");
        busy.clear();

        // ... and only for this turn: Codex owns the chain, but the lower pools serve a "+" request only when the named
        // one cannot, so every later turn is served by the named pool and pays its price, also after a long idle time.
        String previous = responseId(first);
        for (int turn = 0; turn < 3; turn++) {
            if (turn == 2) {
                clock.addAndGet(Duration.ofMinutes(30).toNanos());
            }
            ledger.settled.clear();
            dispatched.clear();
            ResponseEntity<?> next = responses("gpt-5.4@official+", previous, "s1");
            assertServedBy(next, "official", "1", "pinned", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("openai-1");
            ledger.assertCharged("openai:official", 1, "1", "0.039000");
            previous = responseId(next);
        }
        // The same session without a chain is served in the same place.
        ledger.settled.clear();
        assertServedBy(responses("gpt-5.4@official+", null, "s1"), "official", "1", "pinned", "served");
        ledger.assertCharged("openai:official", 1, "1", "0.039000");
    }

    @Test
    void aPlusPinnedChainByTheHeaderGoesBackToTheNamedPoolOnceItIsFree() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiDearerCodex(), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");
        ResponseEntity<?> first = responses("gpt-5.4", null, "s1", "1+");
        assertServedBy(first, "codex", "2", "pinned", "served");
        busy.clear();

        String previous = responseId(first);
        for (int turn = 0; turn < 2; turn++) {
            ledger.settled.clear();
            dispatched.clear();
            ResponseEntity<?> next = responses("gpt-5.4", previous, "s1", "1+");
            assertServedBy(next, "official", "1", "pinned", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("openai-1");
            ledger.assertCharged("openai:official", 1, "1", "0.039000");
            previous = responseId(next);
        }
    }

    @Test
    void aKeyPreferenceForAPoolAndBelowDoesNotKeepAChainInThePoolAStrictPinLeftItIn() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiDearerCodex(), officialOpenAi("openai-1"), codex("codex-1"));
        authenticateAs("OpenAI", "official+");
        ResponseEntity<?> first = responses("gpt-5.4", null, "s1");
        assertServedBy(first, "official", "1", "pinned", "served");

        // One turn names the dearer Codex pool strictly and owns the chain from then on ...
        ledger.settled.clear();
        ResponseEntity<?> strict = responses("gpt-5.4@codex", responseId(first), "s1");
        assertServedBy(strict, "codex", "2", "pinned", "served");
        ledger.assertCharged("openai:codex", 2, "2", "0.078000");

        // ... but the preference of the key names the official pool and Codex only when that one cannot serve.
        String previous = responseId(strict);
        for (int turn = 0; turn < 2; turn++) {
            ledger.settled.clear();
            dispatched.clear();
            ResponseEntity<?> next = responses("gpt-5.4", previous, "s1");
            assertServedBy(next, "official", "1", "pinned", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("openai-1");
            ledger.assertCharged("openai:official", 1, "1", "0.039000");
            previous = responseId(next);
        }
    }

    @Test
    void aPlusPinnedChainKeepsFallingToTheLowerPoolWhileTheNamedPoolIsBusy() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAiDearerCodex(), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");
        String previous = responseId(responses("gpt-5.4@official+", null, "s1"));

        // The named pool is still busy: each turn falls to Codex again, as a request without a chain does.
        for (int turn = 0; turn < 2; turn++) {
            dispatched.clear();
            ResponseEntity<?> next = responses("gpt-5.4@official+", previous, "s1");
            assertServedBy(next, "codex", "2", "pinned", "served");
            assertThat(dispatched).as("turn " + turn).containsExactly("codex-1");
            previous = responseId(next);
        }
    }

    @Test
    void aSessionThatKeepsWorkingWithPinnedRequestsIsNotIdleOnTheMessagesEndpoint() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");
        messages("claude-sonnet-4.5", "session-1");
        assertThat(dispatched).containsExactly("kiro-1");
        busy.clear();

        // The session keeps working, pinned, every six minutes: it is never idle for the ten that end its stay in Kiro.
        for (int turn = 0; turn < 2; turn++) {
            clock.addAndGet(Duration.ofMinutes(6).toNanos());
            assertServedBy(messages("claude-sonnet-4.5@official", "session-1"), "official", "1", "pinned", "served");
        }
        dispatched.clear();
        ResponseEntity<?> automatic = messages("claude-sonnet-4.5", "session-1");

        assertServedBy(automatic, "kiro", "2", "auto", "served");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aSessionThatKeepsWorkingWithPinnedRequestsIsNotIdleOnTheResponsesEndpointEither() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");
        String previous = responseId(responses("gpt-5.4", null, "s1"));
        busy.clear();

        for (int turn = 0; turn < 2; turn++) {
            clock.addAndGet(Duration.ofMinutes(6).toNanos());
            ResponseEntity<?> pinned = responses("gpt-5.4@official", previous, "s1");
            assertServedBy(pinned, "official", "1", "pinned", "served");
            previous = responseId(pinned);
        }
        // The chain and the named session, with and without it, are both still in Codex.
        dispatched.clear();
        assertServedBy(responses("gpt-5.4", previous, "s1"), "codex", "2", "auto", "served");
        assertServedBy(responses("gpt-5.4", null, "s1"), "codex", "2", "auto", "served");
        assertThat(dispatched).containsExactly("codex-1", "codex-1");
    }

    @Test
    void aSessionThatSendsNothingForLongerThanItsBindingLivesGoesBackToTheBestPoolWhateverItsLastRequestsWere() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");
        messages("claude-sonnet-4.5", "session-1");
        busy.clear();
        messages("claude-sonnet-4.5@official", "session-1");

        clock.addAndGet(Duration.ofMinutes(11).toNanos());
        dispatched.clear();
        ResponseEntity<?> automatic = messages("claude-sonnet-4.5", "session-1");

        assertServedBy(automatic, "official", "1", "auto", "served");
        assertThat(dispatched).containsExactly("official-1");
    }

    @Test
    void aUserWhoCanPayForTheTierTheSessionIsInIsNotRefusedForTheTierOfAPinnedTurnOfItsChain() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(true), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");
        String first = responseId(responses("gpt-5.4", null, "s1"));
        busy.clear();
        String second = responseId(responses("gpt-5.4@official", first, "s1"));
        // The balance covers what the Codex tier reserves (about 0.12) and not what the official tier does (about 0.25): a
        // Responses request that names no limit is reserved for the default output of the gateway (16,000 tokens).
        reserveOnlyUpTo("0.18");

        ResponseEntity<?> automatic = responses("gpt-5.4", second, "s1");

        assertServedBy(automatic, "codex", "2", "auto", "served");
        assertThat(ledger.holdLevels()).containsExactly("openai:codex");
        assertThat(ledger.reservedCredits()).isLessThanOrEqualTo(new BigDecimal("0.18"));
    }

    @Test
    void aChainThatCannotLeaveItsOwnerIsStillServedByItEvenWhenTheSessionIsInCodex() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");
        responses("gpt-5.4", null, "s1");
        busy.clear();
        dispatched.clear();
        // Only the account that holds the chain can continue it, whatever the session's pool and whatever it costs.
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("openai-1"));

        ResponseEntity<?> continued = responses("gpt-5.4", "resp_elsewhere", "s1");

        assertServedBy(continued, "official", "1", "auto", "served");
        assertThat(dispatched).containsExactly("openai-1");
        assertThat(ledger.holdLevels()).containsExactly("openai:official");
    }

    // ---- a user who cannot pay the reservation is told what is cheaper ----

    @Test
    void anAutomaticRequestRefusedForTheBalanceNamesThePoolsThatCostLess() {
        serve(anthropic(true), officialKey("official-1"), kiro("kiro-1"), claudeOauth("oauth-1"));
        refuseReservationsForTheBalance();

        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
                assertThat(error.getMessage()).startsWith("余额不足以支付本次请求的预估费用")
                    .contains("claude-sonnet-4.5@kiro").contains("X-Starry-Tier")
                    // The pool nobody may choose is cheaper still, but is not offered.
                    .doesNotContain("internal");
            });

        assertThat(dispatched).isEmpty();
    }

    @Test
    void aVendorThatKeepsItsPoolNamesPrivateDoesNotNameThemToTheUserWhoCannotPay() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), claudeOauth("oauth-1"));
        refuseReservationsForTheBalance();

        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
                // The key form offers such a user no choice of pools, so there is nothing to point at, and no name to give.
                assertThat(error.getMessage()).startsWith("余额不足以支付本次请求的预估费用")
                    .doesNotContain("@").doesNotContain("kiro").doesNotContain("official").doesNotContain("X-Starry-Tier")
                    .doesNotContain("API Key");
            });
    }

    @Test
    void theAdviceNamesThePoolOnTheNameTheKeyMapsAndThatRequestPins() {
        serve(anthropic(true), officialKey("official-1"), kiro("kiro-1"));
        when(modelMapping.apply("my-sonnet", "key-1")).thenReturn("claude-sonnet-4.5");
        refuseReservationsForTheBalance();

        String advice = adviceFor(() -> messages("my-sonnet", "session-1"));

        // A name of the key's own is not a catalog id, yet the suffix is taken off it: the pool is named on the model as
        // the request wrote it, and that is a request which pins (below).
        assertThat(advice).contains("my-sonnet@kiro");
        doReturn(Mono.empty()).when(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
        ResponseEntity<?> followed = messages("my-sonnet@kiro", "session-1");
        assertServedBy(followed, "kiro", "2", "pinned", "served");
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
    }

    @Test
    void theAdviceToAProviderPrefixedModelWorksToo() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(true), officialOpenAi("openai-1"), codex("codex-1"));
        refuseReservationsForTheBalance();

        String advice = adviceFor(() -> chat("openai/gpt-6.1", null));

        assertThat(advice).contains("openai/gpt-6.1@codex");
        doReturn(Mono.empty()).when(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
        ResponseEntity<?> followed = chat("openai/gpt-6.1@codex", null);
        assertServedBy(followed, "codex", "2", "pinned", "served");
        assertThat(dispatched).containsExactly("codex-1");
        assertThat(upstreamModels).containsExactly("openai/gpt-6.1");
    }

    @Test
    void theAdviceDoesNotOfferTheSuffixForAnIdTheKeysMappingIsWrittenAroundAndTheHeaderItGivesWorks() {
        serve(anthropic(true), officialKey("official-1"), kiro("kiro-1"));
        mapWith(rule("claude-sonnet-4@*", "claude-sonnet-4.5"));
        refuseReservationsForTheBalance();

        // Only a key that may call any model sends such an id: one tied to a provider is held to the catalog's ids.
        String advice = adviceFor(() -> messages("claude-sonnet-4@20250514", "session-1"));

        // "claude-sonnet-4@20250514@kiro" is a source of that rule as a whole, so it is mapped and not pinned: the advice
        // names the pool by its id and gives the way that does work (and not the settings of a key that has no preference).
        assertThat(advice).contains("设置请求头 X-Starry-Tier", "kiro（系数 0.6）")
            .doesNotContain("claude-sonnet-4@20250514@kiro").doesNotContain("后缀").doesNotContain("API Key");
        doReturn(Mono.empty()).when(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
        ResponseEntity<?> followed = messages("claude-sonnet-4@20250514", "session-1", "kiro");
        assertServedBy(followed, "kiro", "2", "pinned", "served");
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
        // Whereas the spelling the earlier advice gave is mapped as it stands and served in the tiers' order.
        upstreamModels.clear();
        ResponseEntity<?> spelled = messages("claude-sonnet-4@20250514@kiro", "session-2");
        assertServedBy(spelled, "official", "1", "auto", "served");
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
    }

    @Test
    void theAdviceKeepsTheSuffixForAnIdWhoseMappingSourceIsTheWholeIdAndThatSpellingPins() {
        serve(anthropic(true), officialKey("official-1"), kiro("kiro-1"));
        mapWith(rule("claude-sonnet-4@20250514", "claude-sonnet-4.5"));
        refuseReservationsForTheBalance();

        String advice = adviceFor(() -> messages("claude-sonnet-4@20250514", "session-1"));

        // Only the id as it stands is a source; with a pool on its end the suffix is taken off, and the rule maps the rest.
        assertThat(advice).contains("claude-sonnet-4@20250514@kiro（系数 0.6）", "在模型名后加后缀");
        doReturn(Mono.empty()).when(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
        ResponseEntity<?> followed = messages("claude-sonnet-4@20250514@kiro", "session-1");
        assertServedBy(followed, "kiro", "2", "pinned", "served");
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
    }

    @Test
    void aRequestThatNamedItsPoolIsRefusedForTheBalanceWithoutAdvice() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        refuseReservationsForTheBalance();

        assertThatThrownBy(() -> messages("claude-sonnet-4.5@official", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getMessage()).isEqualTo("余额不足以支付本次请求的预估费用"));
    }

    @Test
    void anUntieredRequestIsRefusedForTheBalanceAsItAlwaysWas() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"));
        refuseReservationsForTheBalance();

        assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getMessage()).isEqualTo("余额不足以支付本次请求的预估费用"));
    }

    // ---- the key's mapping is asked once, by the vendor that serves ----

    @Test
    void aMappingThatPicksAtRandomIsAskedOnceSoTheVendorThatDecidesTheSuffixIsTheVendorThatServes() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));
        // A load-balanced rule: "smart" is Claude for one request and GPT for the next, and nobody configured tiers for GPT.
        AtomicInteger asked = new AtomicInteger();
        when(modelMapping.apply("smart", "key-1"))
            .thenAnswer(invocation -> asked.getAndIncrement() % 2 == 0 ? "claude-sonnet-4.5" : "gpt-5.4");

        for (int round = 0; round < 6; round++) {
            dispatched.clear();
            upstreamModels.clear();
            ResponseEntity<?> response = chat("smart@kiro", null);

            assertThat(dispatched).as("round " + round).containsExactly("kiro-1");
            if (round % 2 == 0) {
                // Claude: the vendor that has the pool takes the suffix off, and the same Claude model is what is served.
                assertServedBy(response, "kiro", "2", "pinned", "served");
                assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
            } else {
                // GPT: the vendor has no tiers, so the @ is part of the id as it was before selectors, and nothing is said.
                assertThat(upstreamModels).containsExactly("smart@kiro");
                assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
            }
        }
        // One answer per request, so the model the request goes on with is the one the strip was decided for.
        verify(modelMapping, times(6)).apply("smart", "key-1");
    }

    @Test
    void aMappingThatPicksTheVendorNobodyConfiguredLeavesTheSuffixInTheIdAndIsNotAskedAgain() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));
        // The first answer is GPT, whose vendor has no tiers; a second question would have answered Claude.
        AtomicInteger asked = new AtomicInteger();
        when(modelMapping.apply("claude-sonnet-4.5", "key-1"))
            .thenAnswer(invocation -> asked.getAndIncrement() == 0 ? "gpt-5.4" : "claude-sonnet-4.5");

        ResponseEntity<?> response = messages("claude-sonnet-4.5@kiro", "session-1");

        // Not pinned, not "ignored": the @ is part of the id, as it is for any vendor without tiers.
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5@kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        verify(modelMapping, times(1)).apply("claude-sonnet-4.5", "key-1");
    }

    @Test
    void aMappingSourceWrittenAroundAnAtIsMappedAndNotReadAsASelector() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        mapWith(rule("claude-sonnet-4@*", "claude-sonnet-4.5"), rule("*@cheap", "claude-haiku-4.5"));

        ResponseEntity<?> dated = messages("claude-sonnet-4@20250514", "session-1");
        ResponseEntity<?> viaChat = chat("claude-sonnet-4@20250514", null);
        ResponseEntity<?> named = messages("claude-sonnet-4.5@cheap", "session-2");

        // The model of the rule's target, from the pool the order gives, and not a pool that does not exist.
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5", "claude-sonnet-4.5", "claude-haiku-4.5");
        assertServedBy(dated, "official", "1", "auto", "served");
        assertServedBy(viaChat, "kiro", "2", "auto", "served");
        assertServedBy(named, "official", "1", "auto", "served");
    }

    @Test
    void aMappingSourceWrittenAroundAnAtIsMappedWhereTheVendorIsSwitchedOffToo() {
        serve(anthropicSwitchedOff(), officialKey("official-1"), kiro("kiro-1"));
        mapWith(rule("claude-sonnet-4@*", "claude-sonnet-4.5"));

        ResponseEntity<?> response = messages("claude-sonnet-4@20250514", "session-1");

        // The target of the rule, not the model in front of the @, and no word about tiers.
        assertThat(upstreamModels).containsExactly("claude-sonnet-4.5");
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
    }

    @Test
    void aMappingPatternWithNoAtOfItsOwnLeavesTheSuffixToBeASelector() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        mapWith(rule("claude-*", "claude-opus-4.5"));

        ResponseEntity<?> response = messages("claude-sonnet-4.5@kiro", "session-1");

        // The rule maps the model in front of the @, as it maps any other, and the pool is the one the client named.
        assertThat(upstreamModels).containsExactly("claude-opus-4.5");
        assertServedBy(response, "kiro", "2", "pinned", "served");
    }

    @Test
    void aBareAtNamesNoModelForAPoolToServe() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        messages("@kiro", "session-1");

        // Nothing is taken off: there would be no model left.
        assertThat(upstreamModels).containsExactly("@kiro");
    }

    // ---- a model the catalog does not list, served by the accounts of a vendor that has the pool ----

    @Test
    void aCodexModelThatIsSentDownTheLastRouteIsPlannedAndPricedByTheTierOfTheCodexAccountsThatServeIt() {
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));

        // "codex-mini-latest" does not start with gpt, so the router sends it down its last route; Codex serves it all the same.
        ResponseEntity<?> auto = responses("codex-mini-latest", null, "session-1");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(auto, "codex", "2", "auto", "served");
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
        assertThat(ledger.holdLevels()).containsExactly("openai:codex");
        assertThat(logged()).singleElement().satisfies(row -> assertThat(row.sourceTierRef()).isEqualTo("openai:codex"));
    }

    @Test
    void aCodexModelThatIsSentDownTheLastRouteCanBePinnedByEveryChannel() {
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));

        ResponseEntity<?> suffixed = responses("codex-mini-latest@codex", null, "session-1");
        ResponseEntity<?> byHeader = responses("codex-mini-latest", null, "session-2", "codex");
        ResponseEntity<?> byLevel = responses("codex-mini-latest@L2+", null, "session-3");

        assertThat(dispatched).containsExactly("codex-1", "codex-1", "codex-1");
        // The suffix is taken off, so the upstream is asked for the model and not for the selector.
        assertThat(upstreamModels).containsOnly("codex-mini-latest");
        assertServedBy(suffixed, "codex", "2", "pinned", "served");
        assertServedBy(byHeader, "codex", "2", "pinned", "served");
        assertServedBy(byLevel, "codex", "2", "pinned", "served");
        assertThat(logged()).extracting(StatsService.RequestLog::model).containsOnly("codex-mini-latest");
        // Codex is the only account kind it has, so the other pool has nothing to offer, by the suffix, and for a hint.
        assertThatThrownBy(() -> responses("codex-mini-latest@official", null, "session-4"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED"));
        ResponseEntity<?> ignored = responses("codex-mini-latest", null, "session-5", "official");
        assertServedBy(ignored, "codex", "2", "auto", "ignored");
    }

    @Test
    void aClaudeIdTheCatalogDoesNotListIsPlannedByTheVendorOfTheKiroAccountsThatServeItOnTheChatEndpointToo() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        assertThat(modelCatalog.models()).noneMatch(spec -> spec.id().equalsIgnoreCase("claude-opus-4-8-20260115"));

        // The Chat endpoint sends a Claude model down the Kiro route, which only Kiro accounts serve.
        ResponseEntity<?> named = chat("claude-opus-4-8-20260115@kiro", null);
        ResponseEntity<?> plain = chat("claude-opus-4-8-20260115", null);
        ResponseEntity<?> short_ = chat("claude@kiro", null);

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "kiro-1");
        assertThat(upstreamModels).containsExactly("claude-opus-4-8-20260115", "claude-opus-4-8-20260115", "claude");
        assertServedBy(named, "kiro", "2", "pinned", "served");
        assertServedBy(plain, "kiro", "2", "auto", "served");
        assertServedBy(short_, "kiro", "2", "pinned", "served");
        // Each was charged the factor of the pool that holds the account that served it.
        assertThat(ledger.settled).allSatisfy(charge -> {
            assertThat(charge.tierRef()).isEqualTo("anthropic:kiro");
            assertThat(charge.factor()).isEqualByComparingTo("0.6");
        });
    }

    @Test
    void aVertexStyleDateOnAClaudeIdOfTheChatEndpointStaysPartOfTheIdAndIsServedInTheTiersOrder() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = chat("claude-3-5-sonnet@20240620", null);

        // As on the Messages endpoint: the @ names no pool of the vendor, so it is part of the id.
        assertThat(upstreamModels).containsExactly("claude-3-5-sonnet@20240620");
        assertServedBy(response, "kiro", "2", "auto", "served");
    }

    // ---- a response chain that cannot leave its owner ----

    @Test
    void aPinnedRequestThatContinuesAChainWhoseOwnerCannotServeIsToldAboutTheOwnerAndNotThatThePoolIsEmpty() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        ProxyAccountEntity outOfQuota = codex("codex-1");
        outOfQuota.setQuotaExhaustedAt(Instant.now());
        // The pool has another account, free the whole time; the chain is the owner's and cannot go there.
        serve(openAi(), officialOpenAi("openai-1"), outOfQuota, codex("codex-2"));
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        for (String selector : new String[] {"gpt-5.4@codex", "gpt-5.4@codex+", "gpt-5.4"}) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", selector).put("input", "do the task").put("previous_response_id", "resp_elsewhere");
            MockServerWebExchange exchange = exchange("/v1/responses", Map.of("x-session-affinity", "session-1"));

            assertThatThrownBy(() -> facade.responses(body, exchange).block())
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCode()).as(selector).isEqualTo("NO_AVAILABLE_ACCOUNT");
                    assertThat(error.getMessage()).isEqualTo("The account that owns the OpenAI response is unavailable");
                    assertThat(error).isInstanceOf(ProxyFacade.ResponseOwnerUnavailableException.class);
                });
            // It is not a wait of a few seconds that helps, as it is for a pool that is busy.
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).as(selector).isNull();
        }
        // The header form of the same pin gives the same answer.
        assertThatThrownBy(() -> responses("gpt-5.4", "resp_elsewhere", "session-2", "codex"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));
        assertThat(dispatched).isEmpty();
        assertThat(ledger.released).hasSize(ledger.reserved.size());
    }

    @Test
    void anEmptyPoolIsToldFromAnUnavailableOwnerByWhatTheFailureIsAndNotByWhatItSays() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        // A failure that is the pool's, even one that happens to read like the owner's.
        doReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT",
            "The account that owns the OpenAI response is unavailable")))
            .when(accountPool).selectInScope(any(), any(), anyString(), any(), anyString(), anyBoolean());
        MockServerWebExchange exchange = exchange("/v1/messages", Map.of(SESSION_HEADER, "session-1"));

        assertThatThrownBy(() -> facade.messages(messagesBody("claude-sonnet-4.5@kiro"), exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
            });
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
    }

    @Test
    void aPinnedRequestWhoseOwnerIsBusyKeepsItsOwnAnswerToo() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"), codex("codex-2"));
        busy.add("codex-1");
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        assertThatThrownBy(() -> responses("gpt-5.4@codex", "resp_elsewhere", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("RESPONSE_OWNER_UNAVAILABLE"));
    }

    @Test
    void aChainThatCannotLeaveItsOwnerReservesForTheOwnersPoolAloneWhicheverPoolThatIs() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));

        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));
        ResponseEntity<?> onCodex = responses("gpt-5.4", "resp_elsewhere", "session-1");

        assertServedBy(onCodex, "codex", "2", "auto", "served");
        assertThat(ledger.holdLevels()).containsExactly("openai:codex");
        assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("openai:codex"));
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
        BigDecimal codexEstimate = estimateOf("openai:codex");

        ledger.settled.clear();
        when(ownerStore.findOwner("resp_other")).thenReturn(Mono.just("openai-1"));
        ResponseEntity<?> onOfficial = responses("gpt-5.4", "resp_other", "session-2");

        assertServedBy(onOfficial, "official", "1", "auto", "served");
        assertThat(ledger.holdLevels()).containsExactly("openai:official");
        assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("openai:official"));
        // The dearer pool is the one a chain that can be replayed anywhere would hold for.
        assertThat(estimateOf("openai:official")).isGreaterThan(codexEstimate);
    }

    @Test
    void aUserWhoCannotPayForAChainThatCannotLeaveItsOwnerIsNotPointedAtPoolsThatCannotServeIt() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(true), officialOpenAi("openai-1"), codex("codex-1"));
        refuseReservationsForTheBalance();

        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("openai-1"));
        String advice = adviceFor(() -> responses("gpt-5.4", "resp_elsewhere", "session-1"));

        // Only the owner's pool can continue the chain, so no other is a cheaper way, and the Codex pool is not named.
        assertThat(advice).isEqualTo("余额不足以支付本次请求的预估费用");
        // An ordinary request of the same user is told, as it always was.
        assertThat(adviceFor(() -> responses("gpt-5.4", null, "session-2"))).contains("gpt-5.4@codex");
        // Nor is anything reserved above what the owner's pool would hold, if the user could pay for it.
        assertThat(ledger.reserved).isEmpty();
    }

    // ---- a pool whose accounts are suspended, pinned by a hint ----

    @Test
    void aKeyPreferenceForAPoolWhoseAccountsAreSuspendedIsNotMovedToAnotherPoolAtAnotherPrice() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        authenticateAs("Anthropic", "kiro");
        // The health tests of the pool fail the only Kiro account.
        accountPool.suspend("kiro-1", "AUTO_TEST_FAILED", "health test failed").block();
        MockServerWebExchange exchange = exchange("/v1/messages", Map.of(SESSION_HEADER, "session-1"));

        assertThatThrownBy(() -> facade.messages(messagesBody("claude-sonnet-4.5"), exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
                assertThat(error.getMessage()).isEqualTo("The tier you chose has no account available right now");
            });

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        // Nothing was dispatched to the pool the user did not choose, and the reservation, made for the pool they did, is whole again.
        assertThat(dispatched).isEmpty();
        assertThat(ledger.reserved).hasSize(1);
        assertThat(ledger.holdLevels()).containsExactly("anthropic:kiro");
        assertThat(ledger.released).hasSize(1);
        assertThat(ledger.settled).isEmpty();
    }

    @Test
    void aHeaderForAPoolWhoseAccountsAreSuspendedIsNotMovedEither() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        accountPool.suspend("official-1", "AUTO_TEST_FAILED", "health test failed").block();

        for (String header : new String[] {"official", "L1", "1"}) {
            assertThatThrownBy(() -> messages("claude-sonnet-4.5", "session-" + header, header))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).as(header).isEqualTo("SOURCE_TIER_UNAVAILABLE"));
        }
        assertThat(dispatched).isEmpty();
        // "or below" is the request that says another pool may take over, and one does.
        ResponseEntity<?> below = messages("claude-sonnet-4.5", "session-below", "official+");
        assertServedBy(below, "kiro", "2", "pinned", "served");
        // So does a request that chose nothing.
        dispatched.clear();
        assertServedBy(messages("claude-sonnet-4.5", "session-auto"), "kiro", "2", "auto", "served");
        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void theSuffixForAPoolWhoseAccountsAreSuspendedIsStillRefusedAsNotOffered() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        accountPool.suspend("official-1", "AUTO_TEST_FAILED", "health test failed").block();

        assertThatThrownBy(() -> messages("claude-sonnet-4.5@official", "session-1"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED");
            });
        assertNothingReservedOrDispatched();
    }

    @Test
    void aSuspendedAccountThatTheRouteDoesNotUseKeepsTheHeaderForItsPoolIgnored() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        accountPool.suspend("official-1", "AUTO_TEST_FAILED", "health test failed").block();

        // An official key is nothing to the Kiro route of the Chat endpoint, suspended or not: the pool has no member there.
        ResponseEntity<?> response = chat("claude-sonnet-4.5", "official");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto", "ignored");
    }

    @Test
    void aPoolWithNoAccountAtAllIsStillIgnoredForAHint() {
        serve(anthropic(), kiro("kiro-1"));

        ResponseEntity<?> response = messages("claude-sonnet-4.5", "session-1", "official");

        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto", "ignored");
    }

    // ---- what a key that cannot have a preference is told ----

    @Test
    void aKeyThatCannotHoldAPreferenceIsNotPointedAtThePreferenceWhenItCannotPay() {
        serve(anthropic(true), officialKey("official-1"), kiro("kiro-1"));
        refuseReservationsForTheBalance();

        authenticateAs("*", null);
        String old = adviceFor(() -> messages("claude-sonnet-4.5", "session-1"));
        authenticateAs("Anthropic", null);
        String tied = adviceFor(() -> messages("claude-sonnet-4.5", "session-2"));

        // The suffix and the header work for it, and its settings have no pool to choose.
        assertThat(old).contains("claude-sonnet-4.5@kiro").contains(TierResponseHeaders.TIER).doesNotContain("API Key");
        assertThat(tied).contains("claude-sonnet-4.5@kiro").contains(TierResponseHeaders.TIER).contains("API Key");
    }

    // ---- a vendor row that holds no tier ----

    @Test
    void aVendorWhoseTiersWereAllDeletedIsAVendorNobodyConfigured() {
        // The row stays when the tiers are deleted one by one, and an empty configuration can be saved.
        SourceTierSnapshot emptied = SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(), List.of(), List.of(), List.of());
        serve(emptied, officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> dated = messages("claude-sonnet-4@20250514", "session-1");
        ResponseEntity<?> byHeader = messages("claude-sonnet-4.5", "session-2", "kiro");
        ResponseEntity<?> suffixed = messages("claude-sonnet-4.5@kiro", "session-3");

        // The id goes to the upstream as it came, and nobody says a word about tiers.
        assertThat(upstreamModels).containsExactly("claude-sonnet-4@20250514", "claude-sonnet-4.5", "claude-sonnet-4.5@kiro");
        for (ResponseEntity<?> response : List.of(dated, byHeader, suffixed)) {
            assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        }
        assertThat(ledger.reserved).allSatisfy(reserved -> assertThat(reserved.pricing().has("tierPlan")).isFalse());
        assertThat(logged()).extracting(StatsService.RequestLog::model)
            .containsExactly("claude-sonnet-4@20250514", "claude-sonnet-4.5", "claude-sonnet-4.5@kiro");
    }

    // ---- fixtures ----

    /** The key of the requests that follow: tied to {@code provider} and preferring {@code tierPreference}. */
    private void authenticateAs(String provider, String tierPreference) {
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(new ApiKeyPrincipal("key-1", null, null, "test", null,
            BigDecimal.ZERO, BigDecimal.ZERO, provider, false, "BALANCE", tierPreference)));
    }

    /** The principal the proxy builds when it allows calls without a key (and the console's own assistant): never billed. */
    private void authenticateAnonymously() {
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(
            new ApiKeyPrincipal("anonymous", null, "ANONYMOUS", "anonymous", null, BigDecimal.ZERO, BigDecimal.ZERO, "*")));
        when(rateLimiter.check("anonymous")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
    }

    /** Refuses every reservation above {@code limit} for the balance, as a user who cannot pay more is. */
    private void reserveOnlyUpTo(String limit) {
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            BigDecimal credits = invocation.getArgument(2);
            ledger.reserved.add(new Reserved(credits, objectMapper.readTree((String) invocation.getArgument(3))));
            return credits.compareTo(new BigDecimal(limit)) > 0
                ? Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "余额不足以支付本次请求的预估费用"))
                : Mono.empty();
        });
    }

    /** The id of the response the facade answered, which the next turn of a Responses chain continues from. */
    private static String responseId(ResponseEntity<?> response) {
        return ((JsonNode) response.getBody()).path("id").asString();
    }

    /** Makes the mapping the facade asks the real one over these rules, which apply to every key. */
    private void mapWith(ModelMappingEntity... rules) {
        ModelMappingRepository repository = mock(ModelMappingRepository.class);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(Flux.just(rules));
        ModelMappingService real = new ModelMappingService(repository, objectMapper);
        real.refresh().block();
        when(modelMapping.apply(anyString(), anyString()))
            .thenAnswer(invocation -> real.apply(invocation.getArgument(0), invocation.getArgument(1)));
        when(modelMapping.hasAtSource(anyString(), anyString()))
            .thenAnswer(invocation -> real.hasAtSource(invocation.getArgument(0), invocation.getArgument(1)));
    }

    private static ModelMappingEntity rule(String source, String target) {
        ModelMappingEntity rule = new ModelMappingEntity();
        rule.setEnabled(true);
        rule.setMappingType("replace");
        rule.setSourceModel(source);
        rule.setTargetModels("[\"" + target + "\"]");
        rule.setPriority(1);
        return rule;
    }

    /** What a request that is refused for the balance is told. */
    private String adviceFor(java.util.function.Supplier<ResponseEntity<?>> request) {
        String[] message = new String[1];
        assertThatThrownBy(request::get).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
            message[0] = error.getMessage();
        });
        return message[0];
    }

    private void refuseReservationsForTheBalance() {
        doReturn(Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "余额不足以支付本次请求的预估费用")))
            .when(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
    }

    private void assertNothingReservedOrDispatched() {
        verify(apiKeyService, never()).reserveUsage(any(), anyString(), any(), anyString());
        assertThat(ledger.reserved).isEmpty();
        assertThat(dispatched).isEmpty();
        verify(admissionStore, never()).acquire(any());
    }

    /** Fills the pool with {@code loaded} and builds the facade over it, the planner and the session bindings. */
    private void serve(SourceTierSnapshot snapshot, ProxyAccountEntity... loaded) {
        accounts.addAll(List.of(loaded));
        AccountSettingsService policySettings = mock(AccountSettingsService.class, invocation ->
            invocation.getMethod().getName().equals("defaultPoolPolicySettings")
                ? new AccountDtos.PoolPolicySettings(false, 5, 2, 50.0, 50.0, 70.0, 5, 1, true)
                : Answers.RETURNS_DEFAULTS.answer(invocation));
        AccountPoolPolicyService policy = new AccountPoolPolicyService(policySettings, mock(DatabaseClient.class), keyResolver);
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenAnswer(invocation -> Flux.fromIterable(List.copyOf(accounts)));
        when(repository.findSwitchedOffByKind()).thenAnswer(invocation -> Flux.fromIterable(List.copyOf(switchedOff)));
        // What the pool does to an account it suspends: it is read, marked and saved, and then it is no longer enabled.
        when(repository.findByAccountId(anyString())).thenAnswer(invocation -> Mono.justOrEmpty(
            java.util.stream.Stream.concat(accounts.stream(), switchedOff.stream())
                .filter(account -> account.getAccountId().equals(invocation.getArgument(0))).findFirst()));
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> {
            ProxyAccountEntity saved = invocation.getArgument(0);
            if (Boolean.FALSE.equals(saved.getEnabled()) && accounts.remove(saved)) {
                switchedOff.add(saved);
            }
            return Mono.just(saved);
        });
        accountPool = spy(new AccountPoolService(repository, null, properties, policy));
        accountPool.refreshCache().block();
        doReturn(Mono.empty()).when(accountPool).recordSuccess(anyString(), anyLong(), any());
        doReturn(Mono.empty()).when(accountPool).recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class),
            any(), anyString(), any(AccountPoolRequestContext.class));
        when(registry.snapshot()).thenReturn(snapshot);
        facade = new ProxyFacade(
            apiKeyService,
            accountPool,
            mock(AccountAutoSuspendService.class),
            accountSettings,
            mock(DisabledAccountScheduledTestService.class),
            modelCatalog,
            modelMapping,
            rateLimiter,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiroClient,
            openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            stateStore,
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
            new TierRuntime(new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing, bindings),
                bindings)
        );
    }

    private ResponseEntity<?> messages(String model, String session) {
        return messages(model, session, null);
    }

    /** A Claude Code request: the Messages endpoint, which routes a Claude model to the Anthropic keys and then Kiro. */
    private ResponseEntity<?> messages(String model, String session, String tierHeader) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(SESSION_HEADER, session);
        if (tierHeader != null) {
            headers.put(TierResponseHeaders.TIER, tierHeader);
        }
        return facade.messages(messagesBody(model), exchange("/v1/messages", headers)).block();
    }

    private ObjectNode messagesBody(String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return body;
    }

    /** An OpenCode request: the Chat Completions endpoint. */
    private ResponseEntity<?> chat(String model, String tierHeader) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return facade.chat(body, exchange("/v1/chat/completions", tierHeader == null ? Map.of() : Map.of(TierResponseHeaders.TIER, tierHeader))).block();
    }

    /** A Codex request: the Responses endpoint. */
    private ResponseEntity<?> responses(String model, String previousResponseId, String session) {
        return responses(model, previousResponseId, session, null);
    }

    private ResponseEntity<?> responses(String model, String previousResponseId, String session, String tierHeader) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("input", "do the task");
        if (previousResponseId != null) {
            body.put("previous_response_id", previousResponseId);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-session-affinity", session);
        if (tierHeader != null) {
            headers.put(TierResponseHeaders.TIER, tierHeader);
        }
        return facade.responses(body, exchange("/v1/responses", headers)).block();
    }

    private ObjectNode geminiBody() {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject().put("role", "user");
        content.putArray("parts").addObject().put("text", "hello");
        body.putObject("generationConfig").put("maxOutputTokens", 4096);
        return body;
    }

    private static MockServerWebExchange exchange(String path, Map<String, String> headers) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post(path);
        headers.forEach(request::header);
        return MockServerWebExchange.from(request);
    }

    private Mono<PlatformCompletionResult> dispatch(ProxyAccountEntity account, String model) {
        dispatched.add(account.getAccountId());
        upstreamModels.add(model);
        return Mono.just(ANSWER);
    }

    private List<StatsService.RequestLog> logged() {
        ArgumentCaptor<StatsService.RequestLog> rows = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, atLeastOnce()).log(rows.capture());
        return rows.getAllValues();
    }

    /** What the plan of the latest reservation says the tier would reserve for the request. */
    private BigDecimal estimateOf(String tierRef) {
        for (JsonNode level : ledger.reserved.getLast().pricing().path("tierPlan").path("levels")) {
            if (level.path("tierRef").asString().equals(tierRef)) {
                return level.path("estimate").decimalValue();
            }
        }
        throw new AssertionError("the plan has no tier " + tierRef);
    }

    private static void assertServedBy(ResponseEntity<?> response, String tierId, String level, String mode, String status) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo(level);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo(mode);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo(status);
    }

    /** The billing calls of the facade as the database would have received them. */
    private static final class Ledger {
        final List<Reserved> reserved = new ArrayList<>();
        final List<String> leasedTo = new ArrayList<>();
        final List<Settled> settled = new ArrayList<>();
        final List<Boolean> released = new ArrayList<>();
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
        void assertCharged(String tierRef, int level, String factor, String credits) {
            assertThat(settled).singleElement().satisfies(charge -> {
                assertThat(charge.tierRef()).isEqualTo(tierRef);
                assertThat(charge.level()).isEqualTo(level);
                assertThat(charge.factor()).isEqualByComparingTo(factor);
                assertThat(charge.credits()).isEqualByComparingTo(credits);
                // Never more than the tier that served would ask, and never more than was held for the request.
                assertThat(charge.credits()).isEqualByComparingTo(QUOTE.scaled(new BigDecimal(factor)).cost(USAGE));
                assertThat(charge.credits()).isLessThanOrEqualTo(reservedCredits());
            });
        }
    }

    private record Reserved(BigDecimal credits, JsonNode pricing) {
    }

    private record Settled(BigDecimal credits, String tierRef, Integer level, BigDecimal factor) {
    }

    /** official (1, x1), kiro (2, x0.6) and internal (3, x0.3, which clients cannot name); the pool names are private. */
    private static SourceTierSnapshot anthropic() {
        return anthropic(false);
    }

    /** The same, with the pool names listed to every user ({@code publicListing}) or not. */
    private static SourceTierSnapshot anthropic(boolean publicListing) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, publicListing, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, "1", true, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, "0.6", true, AccountAllocationCategory.KIRO),
                tier("anthropic", "internal", 3, "0.3", false, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot anthropicSwitchedOff() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", false, false, false, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, "1", true, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, "0.6", true, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of());
    }

    /** official (1, x1) and a dearer codex pool (2, x2): a client may pin it, and it costs more. */
    private static SourceTierSnapshot openAiDearerCodex() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", true, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "2", true, AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of());
    }

    /** official (1, x1), codex (2, x0.5) and spare (3, x3: a second official key put there by hand, {@code openai-2}). */
    private static SourceTierSnapshot openAiThreeTiers() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", true, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "0.5", true, AccountAllocationCategory.CODEX),
                new Tier("openai", "spare", 3, "spare", new BigDecimal("3"), EnumSet.noneOf(AccountAllocationCategory.class), true, true, null)),
            List.of(nativeMember("openai", "openai-2", "spare")),
            List.of(), List.of());
    }

    private static SourceTierSnapshot openAi() {
        return openAi(false);
    }

    private static SourceTierSnapshot openAi(boolean publicListing) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, publicListing, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", true, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "0.5", true, AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of());
    }

    /** Google has no accounts of its own kind, so its two tiers are two Kiro accounts put there by hand. */
    private static SourceTierSnapshot google() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("google", true, false, false, true, 16, true, 5L)),
            List.of(
                new Tier("google", "primary", 1, "primary", new BigDecimal("1"), EnumSet.noneOf(AccountAllocationCategory.class), true, true, null),
                new Tier("google", "backup", 2, "backup", new BigDecimal("0.4"), EnumSet.noneOf(AccountAllocationCategory.class), true, true, null)),
            List.of(
                nativeMember("google", "kiro-1", "primary"),
                nativeMember("google", "kiro-2", "backup")),
            List.of(), List.of());
    }

    private static Member nativeMember(String vendor, String accountId, String tierId) {
        return new Member(vendor, accountId, tierId, null, true, false, false, false, false, true, "NONE", 540, null, null);
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, boolean selectable, AccountAllocationCategory category) {
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), selectable, true, null);
    }

    private static ProxyAccountEntity officialKey(String accountId) {
        return account(accountId, "Anthropic", "api-key");
    }

    private static ProxyAccountEntity claudeOauth(String accountId) {
        return account(accountId, "Anthropic", "oauth");
    }

    private static ProxyAccountEntity kiro(String accountId) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc");
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
    }

    private static ProxyAccountEntity officialOpenAi(String accountId) {
        return account(accountId, "OpenAI", "api-key");
    }

    private static ProxyAccountEntity codex(String accountId) {
        return account(accountId, "OpenAI", "codex");
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
}
