package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
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
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A request for a vendor without tiers makes the very same calls it made before tiers existed (invariant I4): which
 * {@code select*ForPool} of the account pool it asks for, in which order and with which arguments, which accounts
 * admission is asked about, what the retest is given, what the upstream clients are called with, and what is reserved,
 * marked as dispatched, settled and released. The calls of each scenario are in
 * {@code select-for-pool-call-sequences.txt} as its snapshot. They were taken from the facade as it was before tiers
 * (commit dbcc0c0), so a scenario that has to change its snapshot changes what a deployment that never configured tiers
 * does.
 *
 * <p>Each scenario runs against the facade without a tier runtime and against the facade that plans over tiers while
 * nothing is configured, while another vendor is tiered, and while its own vendor is configured but switched off or has
 * no tier switched on. All of them must make the calls of the snapshot: a planner that answers "untiered" does not touch
 * the account pool, look up a price or call anything else.
 */
class SelectForPoolCallSequenceSnapshotTest {

    private static final PlatformCompletionResult ANSWER =
        new PlatformCompletionResult("OK", new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0));
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));
    private static final Pattern UUID_TEXT = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** How the facade is given its tier runtime. */
    enum Variant {
        /** What a release before tiers has, and what a deployment that cannot run tiers is given. */
        WITHOUT_RUNTIME,
        /** The planner is there and nothing is configured. */
        NOTHING_CONFIGURED,
        /** The planner is there and a vendor none of the scenarios uses is tiered. */
        ANOTHER_VENDOR_TIERED,
        /** The planner is there and every vendor the scenarios use is configured but switched off. */
        OWN_VENDOR_SWITCHED_OFF,
        /** The planner is there and every vendor the scenarios use is switched on with all of its tiers switched off. */
        OWN_VENDOR_WITHOUT_AN_ENABLED_TIER
    }

    // ---- the Kiro route: a Claude model on Chat Completions ----

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theKiroRouteAsksTheKiroPoolOnceAndSettlesTheStandardPrice(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectKiroAccountForPool", kiro("kiro-1"));

        run.chat("claude-sonnet-4.5");

        run.assertJournal("kiroRoute");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aFailureOnTheKiroRouteAsksTheKiroPoolAgainWithTheAccountsAlreadyTried(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectKiroAccountForPool", kiro("kiro-1"), kiro("kiro-2"));
        run.upstreamFails("kiro-1", new UpstreamException(429, "slow down", true));

        run.chat("claude-sonnet-4.5");

        run.assertJournal("kiroRouteRetry");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void anAdmissionRefusalAsksTheKiroPoolAgainWithTheRefusedAccountLeftOut(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectKiroAccountForPool", kiro("kiro-1"), kiro("kiro-2"));
        run.admissionRefuses("kiro-1");

        run.chat("claude-sonnet-4.5");

        run.assertJournal("kiroRouteAdmissionRefusal");
    }

    // ---- the Anthropic route: a Claude model on the Messages endpoint ----

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theAnthropicRouteAsksTheAnthropicPoolFirst(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectAnthropicAccountForPool", account("anthropic-1", AccountPlatforms.ANTHROPIC_PROVIDER,
            AccountPlatforms.OPENAI_API_KEY_AUTH, null));

        run.messages("claude-sonnet-4.5");

        run.assertJournal("anthropicRoute");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theAnthropicRouteFallsBackToTheKiroPoolWhenItsOwnHasNoAccount(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectKiroAccountForPool", kiro("kiro-1"));

        run.messages("claude-sonnet-4.5");

        run.assertJournal("anthropicRouteFallsBackToKiro");
    }

    // ---- the OpenAI route ----

    @ParameterizedTest
    @EnumSource(Variant.class)
    void withCodexIsolationAnOpenAiRequestAsksForAnOfficialKey(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectOpenAiApiKeyAccountForPool", openAi("openai-1"));

        run.chat("gpt-5.4");

        run.assertJournal("openAiIsolated");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void withoutCodexIsolationAnOpenAiRequestAsksTheWholeOpenAiPool(Variant variant) {
        Run run = new Run(variant);
        run.policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        run.selects("selectOpenAiAccountForPool", openAi("openai-1"));

        run.chat("gpt-5.4");

        run.assertJournal("openAiNotIsolated");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aRequestThatPrefersCodexAsksTheCodexPool(Variant variant) {
        Run run = new Run(variant);
        run.policy = new RoutingPolicySettings(true, true, false, false, 1, 3, 3, 2, 2);
        run.selects("selectCodexOpenAiAccountForPool", codex("codex-1"));

        run.chat("gpt-5.4");

        run.assertJournal("codexPreferred");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void withoutCodexIsolationAMissInTheCodexPoolFallsToTheOpenAiPool(Variant variant) {
        Run run = new Run(variant);
        run.policy = new RoutingPolicySettings(false, true, false, false, 1, 3, 3, 2, 2);
        run.selects("selectOpenAiAccountForPool", openAi("openai-1"));

        run.chat("gpt-5.4");

        run.assertJournal("codexMissFallsToOpenAi");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aDeepSeekModelAsksTheDeepSeekPool(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectDeepSeekOpenAiAccountForPool", deepSeek("deepseek-1"));

        run.chat("deepseek-v4-flash");

        run.assertJournal("deepSeek");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aCustomOpenAiCompatibleModelAsksTheCompatiblePool(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectOpenAiCompatibleAccountForPool", account("glm-1", "GLM", AccountPlatforms.OPENAI_API_KEY_AUTH,
            "https://open.bigmodel.cn/api/paas/v4"));

        run.chat("glm5.3");

        run.assertJournal("openAiCompatible");
    }

    // ---- the Responses endpoint and its chains ----

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aResponseChainAsksForItsOwnerByIdFirst(Variant variant) {
        Run run = new Run(variant);
        run.chainOwnedBy("openai-1");
        run.selects("selectOpenAiAccountByIdForPool", openAi("openai-1"));

        run.responses("resp_known");

        run.assertJournal("responseOwner");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aChainThatCanMoveFallsBackToThePoolWhenItsOwnerIsGone(Variant variant) {
        Run run = new Run(variant);
        run.chainOwnedBy("openai-gone");
        run.selects("selectOpenAiApiKeyAccountForPool", openAi("openai-2"));

        run.responses("resp_known");

        run.assertJournal("responseOwnerGone");
    }

    // ---- no account at all ----

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aRoundWithoutAnAccountRetestsOnceAndSelectsAgainWhenSomethingCameBack(Variant variant) {
        Run run = new Run(variant);
        run.selects("selectKiroAccountForPool", NO_ACCOUNT, kiro("kiro-1"));
        run.retest = Mono.just(new AccountDtos.TestAccountsResponse(1, 1, 0, 0, Instant.EPOCH, Instant.EPOCH, List.of()));

        run.chat("claude-sonnet-4.5");

        run.assertJournal("retestBringsBackAnAccount");
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aRoundWithoutAnAccountAndNothingToRetestEndsWithNoAvailableAccount(Variant variant) {
        Run run = new Run(variant);

        assertThatThrownBy(() -> run.chat("claude-sonnet-4.5"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        run.assertJournal("noAccount");
    }

    // ---- fixtures ----

    private static final Object NO_ACCOUNT = new Object();

    /** One scenario against one variant of the facade; everything the facade calls on its collaborators is written to the journal. */
    private static final class Run {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        private final Variant variant;
        private final List<String> journal = new ArrayList<>();
        private final Map<String, Deque<Object>> selections = new HashMap<>();
        private final Map<String, Deque<Throwable>> upstreamFailures = new HashMap<>();
        private final Set<String> refused = new HashSet<>();
        private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
        private final List<ProxyAccountEntity> panel = List.of(
            account("panel-anthropic", AccountPlatforms.ANTHROPIC_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH, null),
            kiro("panel-kiro"),
            openAi("panel-openai"),
            codex("panel-codex"),
            deepSeek("panel-deepseek"),
            account("panel-glm", "GLM", AccountPlatforms.OPENAI_API_KEY_AUTH, "https://open.bigmodel.cn/api/paas/v4"));

        RoutingPolicySettings policy = RoutingPolicySettings.defaults();
        Mono<AccountDtos.TestAccountsResponse> retest = Mono.empty();

        private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);
        private final ProxyFacade facade;

        Run(Variant variant) {
            this.variant = variant;
            modelCatalog.applyCustomizations(List.of(new ModelCatalogCustomization("glm5.3", "GLM 5.3", null, "GLM", "GLM 5",
                "glm5.3", "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 128000, "GLM", true, null)));
            facade = facade();
        }

        private ProxyFacade facade() {
            ApiKeyService apiKeyService = recording(ApiKeyService.class, "keys");
            AccountPoolService accountPool = recording(AccountPoolService.class, "pool");
            AccountAdmissionStore admissionStore = recording(AccountAdmissionStore.class, "admission");
            DisabledAccountScheduledTestService retestService = recording(DisabledAccountScheduledTestService.class, "retest");
            KiroUpstreamClient kiroClient = recording(KiroUpstreamClient.class, "kiro");
            OpenAiUpstreamClient openAiClient = recording(OpenAiUpstreamClient.class, "openai");
            AnthropicUpstreamClient anthropicClient = recording(AnthropicUpstreamClient.class, "anthropic");
            PaymentSettingsService payments = recording(PaymentSettingsService.class, "payments");
            ModelPricingService pricing = recording(ModelPricingService.class, "pricing");
            AccountSettingsService accountSettings = mock(AccountSettingsService.class);
            ModelMappingService modelMapping = mock(ModelMappingService.class);
            RateLimiterService rateLimiter = mock(RateLimiterService.class);
            StatsService statsService = mock(StatsService.class);
            OpenAiResponseOwnerStore ownerStore = mock(OpenAiResponseOwnerStore.class);
            when(accountSettings.routingPolicy()).thenAnswer(invocation -> Mono.just(policy));
            when(modelMapping.apply(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
            when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
            when(statsService.log(any())).thenReturn(Mono.empty());
            when(ownerStore.remember(anyString(), anyString()))
                .thenReturn(Mono.empty());
            when(ownerStore.findOwner(anyString())).thenReturn(Mono.empty());

            KiroPayloadFactory payloadFactory = new KiroPayloadFactory(objectMapper, modelCatalog);
            OpenAiRequestAdapter openAiAdapter = new OpenAiRequestAdapter(objectMapper, modelCatalog);
            OpenAiResponseFactory openAiFactory = new OpenAiResponseFactory(objectMapper);
            AnthropicRequestAdapter anthropicAdapter = new AnthropicRequestAdapter(objectMapper, modelCatalog);
            AnthropicResponseFactory anthropicFactory = new AnthropicResponseFactory(objectMapper);
            GeminiRequestAdapter geminiAdapter = new GeminiRequestAdapter(objectMapper);
            GeminiResponseFactory geminiFactory = new GeminiResponseFactory(objectMapper);
            if (variant == Variant.WITHOUT_RUNTIME) {
                return new ProxyFacade(apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings,
                    retestService, modelCatalog, modelMapping, rateLimiter, payloadFactory, kiroClient, openAiClient,
                    openAiAdapter, openAiFactory, stateStore, anthropicAdapter, anthropicFactory, anthropicClient,
                    geminiAdapter, geminiFactory, payments, pricing, statsService, admissionStore, ownerStore);
            }
            SourceTierRegistry registry = mock(SourceTierRegistry.class);
            when(registry.snapshot()).thenReturn(snapshot(variant));
            TierRuntime runtime = new TierRuntime(new DefaultSourceTierPlanner(
                registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled()));
            return new ProxyFacade(apiKeyService, accountPool, mock(AccountAutoSuspendService.class), accountSettings,
                retestService, modelCatalog, modelMapping, rateLimiter, payloadFactory, kiroClient, openAiClient,
                openAiAdapter, openAiFactory, stateStore, anthropicAdapter, anthropicFactory, anthropicClient,
                geminiAdapter, geminiFactory, payments, pricing, statsService, admissionStore, ownerStore, runtime);
        }

        private <T> T recording(Class<T> type, String label) {
            return mock(type, withSettings().defaultAnswer(invocation -> {
                String call = label + "." + invocation.getMethod().getName() + "(" + arguments(invocation, label) + ")";
                if (!call.startsWith("keys.authenticate(")) {
                    journal.add(call);
                }
                return answer(label, invocation);
            }));
        }

        private Object answer(String label, InvocationOnMock invocation) {
            String method = invocation.getMethod().getName();
            return switch (label) {
                case "keys" -> method.equals("authenticate") ? Mono.just(principal) : Mono.empty();
                case "pool" -> method.startsWith("select") ? selection(method) : emptyOf(invocation);
                case "admission" -> method.equals("acquire") ? Mono.just(decision(invocation.getArgument(0))) : Mono.empty();
                case "retest" -> retest;
                case "kiro", "openai", "anthropic" -> upstream(invocation.getArgument(0));
                case "payments" -> Mono.just(paymentConfig());
                case "pricing" -> Mono.just(QUOTE);
                default -> emptyOf(invocation);
            };
        }

        private Object selection(String method) {
            Deque<Object> queue = selections.get(method);
            Object next = queue == null ? null : queue.pollFirst();
            if (next instanceof ProxyAccountEntity account) {
                return Mono.just(account);
            }
            return Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available upstream account"));
        }

        private AdmissionDecision decision(AdmissionRequest request) {
            return refused.contains(request.accountId())
                ? new AdmissionDecision(false, null, "ACCOUNT_BUSY", null, null)
                : new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600));
        }

        private Mono<PlatformCompletionResult> upstream(ProxyAccountEntity account) {
            Deque<Throwable> failures = upstreamFailures.get(account.getAccountId());
            Throwable failure = failures == null ? null : failures.pollFirst();
            return failure == null ? Mono.just(ANSWER) : Mono.error(failure);
        }

        private static Object emptyOf(InvocationOnMock invocation) {
            Class<?> type = invocation.getMethod().getReturnType();
            if (Mono.class.isAssignableFrom(type)) {
                return Mono.empty();
            }
            if (Flux.class.isAssignableFrom(type)) {
                return Flux.empty();
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == Optional.class) {
                return Optional.empty();
            }
            return null;
        }

        void selects(String method, Object... outcomes) {
            selections.computeIfAbsent(method, key -> new ArrayDeque<>()).addAll(List.of(outcomes));
        }

        void upstreamFails(String accountId, Throwable failure) {
            upstreamFailures.computeIfAbsent(accountId, key -> new ArrayDeque<>()).add(failure);
        }

        void admissionRefuses(String accountId) {
            refused.add(accountId);
        }

        /** The chain of {@code resp_known} was answered by {@code ownerId}, and the gateway still has its history. */
        void chainOwnedBy(String ownerId) {
            ObjectNode earlier = objectMapper.createObjectNode().put("id", "resp_known");
            earlier.putArray("output");
            stateStore.remember(earlier, objectMapper.createObjectNode().put("input", "first"), ownerId);
        }

        void chat(String model) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            facade.chat(body, exchange("/v1/chat/completions")).block();
        }

        void messages(String model) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model).put("max_tokens", 4096);
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            facade.messages(body, exchange("/v1/messages")).block();
        }

        void responses(String previousResponseId) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "gpt-5.4").put("input", "continue").put("previous_response_id", previousResponseId);
            facade.responses(body, exchange("/v1/responses")).block();
        }

        void assertJournal(String scenario) {
            assertThat(SNAPSHOTS).containsKey(scenario);
            assertThat(journal).as(scenario + " with " + variant).containsExactlyElementsOf(SNAPSHOTS.get(scenario));
        }

        // ---- how a call is written down ----

        private String arguments(InvocationOnMock invocation, String label) {
            // The body sent to Kiro carries a conversation id and the time, so only the other upstreams' bodies are compared.
            return Arrays.stream(invocation.getArguments())
                .map(value -> value instanceof JsonNode && label.equals("kiro") ? "json" : render(value))
                .collect(Collectors.joining(", "));
        }

        @SuppressWarnings("unchecked")
        private String render(Object value) {
            return switch (value) {
                case null -> "null";
                case String text -> UUID_TEXT.matcher(text).replaceAll("<id>");
                case ProxyAccountEntity account -> account.getAccountId();
                case AccountPoolRequestContext context -> "ctx[" + context.requestedModel() + "/" + context.upstreamModel() + "/"
                    + context.providerHint() + "/epoch=" + context.circuitEpoch() + "]";
                case AdmissionRequest request -> "request[account=" + request.accountId() + " in=" + request.reservedInputTokens()
                    + " out=" + request.reservedOutputTokens() + " model=" + request.modelScope() + " endpoint=" + request.endpointScope() + "]";
                case Predicate<?> filter -> "accepts" + panel.stream().filter(account -> ((Predicate<ProxyAccountEntity>) filter).test(account))
                    .map(ProxyAccountEntity::getAccountId).toList();
                case JsonNode json -> "json#" + digest(json.toString());
                case ApiKeyPrincipal key -> "principal:" + key.keyId();
                case BigDecimal number -> number.toPlainString();
                case Collection<?> items -> items.stream().map(this::render).toList().toString();
                case Number number -> number.toString();
                case Boolean flag -> flag.toString();
                case Enum<?> constant -> constant.name();
                default -> value.getClass().getSimpleName();
            };
        }

        private static String digest(String text) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 4);
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static SourceTierSnapshot snapshot(Variant variant) {
        return switch (variant) {
            case ANOTHER_VENDOR_TIERED -> configured(List.of("google"), true, true);
            case OWN_VENDOR_SWITCHED_OFF -> configured(SCENARIO_VENDORS, false, true);
            case OWN_VENDOR_WITHOUT_AN_ENABLED_TIER -> configured(SCENARIO_VENDORS, true, false);
            default -> SourceTierSnapshot.EMPTY;
        };
    }

    /** The vendors the scenarios send their requests to. */
    private static final List<String> SCENARIO_VENDORS = List.of("anthropic", "openai", "deepseek", "glm");

    private static SourceTierSnapshot configured(List<String> vendors, boolean vendorEnabled, boolean tiersEnabled) {
        List<VendorConfig> configs = new ArrayList<>();
        List<Tier> tiers = new ArrayList<>();
        for (String vendor : vendors) {
            configs.add(new VendorConfig(vendor, vendorEnabled, false, false, true, 16, true, 3L));
            tiers.add(tier(vendor, "official", 1, AccountAllocationCategory.OFFICIAL_API_KEY, tiersEnabled));
            tiers.add(tier(vendor, "second", 2, AccountAllocationCategory.KIRO, tiersEnabled));
        }
        return SourceTierSnapshot.of(3L, configs, tiers, List.of(), List.of(), List.of());
    }

    private static Tier tier(String vendor, String tierId, int level, AccountAllocationCategory category, boolean enabled) {
        return new Tier(vendor, tierId, level, tierId, BigDecimal.ONE, EnumSet.of(category), true, enabled, null);
    }

    private static ProxyAccountEntity kiro(String accountId) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc", null);
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
    }

    private static ProxyAccountEntity openAi(String accountId) {
        return account(accountId, AccountPlatforms.OPENAI_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH, null);
    }

    private static ProxyAccountEntity codex(String accountId) {
        return account(accountId, AccountPlatforms.OPENAI_PROVIDER, AccountPlatforms.OPENAI_CODEX_AUTH, null);
    }

    private static ProxyAccountEntity deepSeek(String accountId) {
        return account(accountId, AccountPlatforms.DEEPSEEK_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH,
            AccountPlatforms.DEFAULT_DEEPSEEK_BASE_URL);
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("token");
        account.setEnabled(true);
        return account;
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }

    /** The calls of each scenario, taken from the facade before tiers: the file says where from. */
    private static final Map<String, List<String>> SNAPSHOTS = snapshots();

    private static Map<String, List<String>> snapshots() {
        Map<String, List<String>> snapshots = new LinkedHashMap<>();
        List<String> current = null;
        try (InputStream file = SelectForPoolCallSequenceSnapshotTest.class.getResourceAsStream("select-for-pool-call-sequences.txt")) {
            for (String line : new String(Objects.requireNonNull(file).readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                if (line.startsWith("@@ ")) {
                    current = new ArrayList<>();
                    snapshots.put(line.substring(3).strip(), current);
                } else {
                    current.add(line);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return snapshots;
    }
}
