package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.PoolCell;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierRoute;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cells a tier is made of must be the ones the untiered route would pick from, or a tiered request could be served
 * by an account its route never uses. Here the facade's own {@code selectAccount} is run for every combination of the
 * routing facts, with an account pool that has no account and notes which pool each attempt asks for, and the pools it
 * asked for are compared with the cells the planner derives from the request the facade builds for the same facts.
 */
class ProxyFacadeTierUnitsTest {

    private static final Map<PoolCell, String> SELECTORS = Map.of(
        PoolCell.DEEPSEEK, "selectDeepSeekOpenAiAccountForPool",
        PoolCell.OPENAI_COMPATIBLE, "selectOpenAiCompatibleAccountForPool",
        PoolCell.CODEX, "selectCodexOpenAiAccountForPool",
        PoolCell.OFFICIAL_OPENAI, "selectOpenAiApiKeyAccountForPool",
        PoolCell.OFFICIAL_OR_CODEX, "selectOpenAiAccountForPool",
        PoolCell.ANTHROPIC_KEY, "selectAnthropicAccountForPool",
        PoolCell.KIRO_COMPATIBLE, "selectKiroAccountForPool"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    static Stream<Arguments> routingFacts() {
        List<Arguments> facts = new ArrayList<>();
        for (TierRoute route : TierRoute.values()) {
            // DeepSeek and the OpenAI-compatible models only exist on the OpenAI route.
            for (boolean deepSeek : route == TierRoute.OPENAI ? new boolean[] {false, true} : new boolean[] {false}) {
                for (boolean compatible : route == TierRoute.OPENAI ? new boolean[] {false, true} : new boolean[] {false}) {
                    // A Codex-only Responses model can ask for Codex accounts on any route.
                    for (boolean codex : new boolean[] {false, true}) {
                        for (boolean isolated : new boolean[] {false, true}) {
                            facts.add(Arguments.of(route, deepSeek, compatible, codex, isolated));
                        }
                    }
                }
            }
        }
        return facts.stream();
    }

    @ParameterizedTest(name = "route={0} deepSeek={1} compatibleModel={2} preferCodex={3} isolateCodexPool={4}")
    @MethodSource("routingFacts")
    void theCellsOfThePlanAreThePoolsTheRouteSelectsFrom(
        TierRoute route, boolean deepSeek, boolean compatible, boolean codex, boolean isolated
    ) throws Exception {
        AccountPoolService accountPool = mock(AccountPoolService.class, invocation ->
            invocation.getMethod().getReturnType() == Mono.class
                ? Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "no account"))
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
        ProxyFacade facade = facade(accountPool);
        String model = deepSeek ? "deepseek-chat" : compatible ? "openai-compatible/some-model" : "gpt-5.4";
        AccountPoolRequestContext poolContext = new AccountPoolRequestContext("request-1", model, model, "Provider");
        AccountDtos.RoutingPolicySettings policy =
            new AccountDtos.RoutingPolicySettings(isolated, false, false, false, 1, 3, 3, 2, 2);
        Object facadeRoute = facadeRoute(route);
        ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);
        ObjectNode body = objectMapper.createObjectNode().put("model", model);

        TierPlanRequest request = (TierPlanRequest) method("tierPlanRequest").invoke(facade, principal, poolContext, model,
            facadeRoute, deepSeek, codex, false, policy, body, null, false);
        ((Mono<?>) method("selectAccount").invoke(facade, facadeRoute, "key-1", List.<String>of(), codex, deepSeek,
            poolContext, policy)).onErrorResume(error -> Mono.empty()).block();

        List<String> asked = mockingDetails(accountPool).getInvocations().stream()
            .map(invocation -> invocation.getMethod().getName())
            .filter(name -> name.startsWith("select"))
            .toList();
        List<String> cells = DefaultSourceTierPlanner.nativeUnits(request).stream().map(SELECTORS::get).toList();
        assertThat(asked).as("pools asked for by selectAccount").isEqualTo(cells);
    }

    private static Object facadeRoute(TierRoute route) {
        Class<?> type = Arrays.stream(ProxyFacade.class.getDeclaredClasses())
            .filter(candidate -> candidate.getSimpleName().equals("UpstreamRoute"))
            .findFirst()
            .orElseThrow();
        return Arrays.stream(type.getEnumConstants())
            .filter(constant -> ((Enum<?>) constant).name().equals(route.name()))
            .findFirst()
            .orElseThrow();
    }

    private static Method method(String name) {
        Method found = Arrays.stream(ProxyFacade.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
        found.setAccessible(true);
        return found;
    }

    private ProxyFacade facade(AccountPoolService accountPool) {
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        return new ProxyFacade(
            mock(ApiKeyService.class),
            accountPool,
            mock(AccountAutoSuspendService.class),
            mock(AccountSettingsService.class),
            mock(DisabledAccountScheduledTestService.class),
            modelCatalog,
            mock(ModelMappingService.class),
            mock(RateLimiterService.class),
            new KiroPayloadFactory(objectMapper, modelCatalog),
            mock(KiroUpstreamClient.class),
            mock(OpenAiUpstreamClient.class),
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            new OpenAiResponseStateStore(objectMapper),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            mock(AnthropicUpstreamClient.class),
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            mock(PaymentSettingsService.class),
            mock(ModelPricingService.class),
            mock(StatsService.class)
        );
    }
}
