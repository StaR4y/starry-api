package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountFailureScope;
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
import tech.starryapi.stats.StatsService;

class ProxyFacadeAdmissionLifecycleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void admissionUsesCurrentLifecycleStateAndCarriesScopedCooldownKeys() throws Exception {
        Fixture fixture = fixture();
        fixture.successfulUpstream();

        fixture.facade().chat(request(), exchange()).block();

        ArgumentCaptor<AccountAdmissionStore.AdmissionRequest> requestCaptor =
            ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionRequest.class);
        verify(fixture.admissionStore()).acquire(requestCaptor.capture());
        AccountAdmissionStore.AdmissionRequest admission = requestCaptor.getValue();
        assertThat(admission.expectedStateVersion()).isNull();
        assertThat(admission.modelScope()).isEqualTo("gpt-5");
        assertThat(admission.endpointScope()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void clientCancellationAbandonsADispatchedLease() throws Exception {
        Fixture fixture = fixture();
        when(fixture.openAiUpstreamClient().complete(
            eq(fixture.account()),
            any(JsonNode.class),
            eq("gpt-5")
        )).thenReturn(Mono.never());

        Disposable subscription = fixture.facade().chat(request(), exchange()).subscribe();
        verify(fixture.admissionStore()).markDispatched(fixture.leaseId());

        subscription.dispose();

        verify(fixture.admissionStore()).abandon(fixture.leaseId(), "CLIENT_CANCELLED");
        verify(fixture.admissionStore(), never()).reconcile(any());
    }

    @Test
    void pricingFailureStopsBeforeAdmissionAndUpstreamDispatch() throws Exception {
        Fixture fixture = fixture();
        fixture.successfulUpstream();
        when(fixture.paymentSettingsService().config())
            .thenReturn(Mono.error(new IllegalStateException("pricing unavailable")));

        assertThatThrownBy(() -> fixture.facade().chat(request(), exchange()).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("pricing unavailable");

        verify(fixture.admissionStore(), never()).acquire(any());
        verify(fixture.admissionStore(), never()).reconcile(any());
        verify(fixture.openAiUpstreamClient(), never()).complete(any(), any(), anyString());
        verify(fixture.admissionStore(), never()).abandon(any(), anyString());
    }

    @Test
    void unpricedModelStopsBeforeReservationAndUpstreamDispatch() throws Exception {
        Fixture fixture = fixture();
        when(fixture.modelPricingService().billingQuote(anyString(), eq(false), eq(BigDecimal.ONE)))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PRICE_UNAVAILABLE",
                "Model price is unavailable")));

        assertThatThrownBy(() -> fixture.facade().chat(request(), exchange()).block())
            .isInstanceOf(ApiException.class).hasMessage("Model price is unavailable");
        verify(fixture.apiKeyService(), never()).reserveUsage(any(), anyString(), any(), anyString());
        verify(fixture.admissionStore(), never()).acquire(any());
        verify(fixture.openAiUpstreamClient(), never()).complete(any(), any(), anyString());
    }

    @Test
    void dispatchMarkFailureReleasesTheUnsentLeaseWithoutCallingUpstream() throws Exception {
        Fixture fixture = fixture();
        when(fixture.admissionStore().markDispatched(fixture.leaseId()))
            .thenReturn(Mono.error(new IllegalStateException("dispatch mark failed")));

        assertThatThrownBy(() -> fixture.facade().chat(request(), exchange()).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("dispatch mark failed");

        verify(fixture.admissionStore()).releaseUnsent(fixture.leaseId());
        verify(fixture.openAiUpstreamClient(), never()).complete(any(), any(), anyString());
        verify(fixture.admissionStore(), never()).abandon(any(), anyString());
    }

    @Test
    void responseConstructionFailureCannotReopenAReconciledLease() throws Exception {
        Fixture fixture = fixture();
        fixture.successfulUpstream();
        doThrow(new IllegalStateException("response construction failed"))
            .when(fixture.openAiResponseFactory())
            .chatCompletion(eq("gpt-5"), any(PlatformCompletionResult.class));

        assertThatThrownBy(() -> fixture.facade().chat(request(), exchange()).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("response construction failed");

        ArgumentCaptor<AccountAdmissionStore.AdmissionResult> resultCaptor =
            ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionResult.class);
        verify(fixture.admissionStore()).reconcile(resultCaptor.capture());
        assertThat(resultCaptor.getValue().success()).isTrue();
        verify(fixture.admissionStore(), never()).abandon(any(), anyString());
    }

    @Test
    void reconciliationFailureFallsBackToAbandonWithoutFailingTheCompletedRequest() throws Exception {
        Fixture fixture = fixture();
        fixture.successfulUpstream();
        when(fixture.admissionStore().reconcile(any()))
            .thenReturn(Mono.error(new IllegalStateException("database unavailable")));

        assertThat(fixture.facade().chat(request(), exchange()).block()).isNotNull();

        verify(fixture.admissionStore()).abandon(fixture.leaseId(), "RECONCILIATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void requestScopedFailuresNeverUpdateGlobalAccountHealth(int statusCode) throws Exception {
        Fixture fixture = fixture();
        when(fixture.openAiUpstreamClient().complete(
            eq(fixture.account()),
            any(JsonNode.class),
            eq("gpt-5")
        )).thenReturn(Mono.error(new UpstreamException(statusCode, "invalid request", false)));

        assertThatThrownBy(() -> fixture.facade().chat(request(), exchange()).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getStatus()).isEqualTo(HttpStatus.valueOf(statusCode))
            );

        verify(fixture.accountPoolService(), never()).recordRequestFailure(
            anyString(),
            any(),
            any(),
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        ArgumentCaptor<AccountAdmissionStore.AdmissionResult> resultCaptor =
            ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionResult.class);
        verify(fixture.admissionStore()).reconcile(resultCaptor.capture());
        assertThat(resultCaptor.getValue().failureScope()).isEqualTo(AccountFailureScope.REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "1.5", "\"4096\"", "1000001", "null"})
    void invalidOutputLimitIsRejectedBeforeReservation(String limit) throws Exception {
        Fixture fixture = fixture();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        body.set("max_tokens", objectMapper.readTree(limit));
        assertThatThrownBy(() -> fixture.facade().chat(body, exchange()).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("MAX_OUTPUT_TOKENS_INVALID"));
        verify(fixture.apiKeyService(), never()).reserveUsage(any(), anyString(), any(), anyString());
        verify(fixture.admissionStore(), never()).acquire(any());
    }

    @Test
    void billingFailureStopsBeforeUpstreamAdmission() throws Exception {
        Fixture fixture = fixture();
        when(fixture.apiKeyService().reserveUsage(any(), anyString(), any(), anyString()))
            .thenReturn(Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "Insufficient funds")));
        assertThatThrownBy(() -> fixture.facade().chat(request(), exchange()).block()).isInstanceOf(ApiException.class);
        verify(fixture.admissionStore(), never()).acquire(any());
        verify(fixture.openAiUpstreamClient(), never()).complete(any(), any(), anyString());
    }

    @Test
    void defaultOutputLimitIsBoundedAndOrdinaryTextDoesNotTriggerMediaEstimate() throws Exception {
        Fixture fixture = fixture(); fixture.successfulUpstream();
        var body = objectMapper.readTree("""
            {"model":"gpt-5","messages":[{"role":"user","content":"Explain image_url and input_file fields"}]}
            """);
        fixture.facade().chat(body, exchange()).block();
        var requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient()).complete(any(), requestCaptor.capture(), anyString());
        assertThat(requestCaptor.getValue().path("max_tokens").longValue()).isEqualTo(4096);
        var hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService()).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getValue()).isLessThan(new BigDecimal("10000"));
        assertThat(body.has("max_tokens")).isFalse();
    }

    private Fixture fixture() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
        DisabledAccountScheduledTestService disabledAccountScheduledTestService =
            mock(DisabledAccountScheduledTestService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        AnthropicUpstreamClient anthropicUpstreamClient = mock(AnthropicUpstreamClient.class);
        PaymentSettingsService paymentSettingsService = mock(PaymentSettingsService.class);
        PaymentSettingsService.PaymentConfig paymentConfig = mock(PaymentSettingsService.PaymentConfig.class);
        ModelPricingService modelPricingService = mock(ModelPricingService.class);
        StatsService statsService = mock(StatsService.class);
        AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
        OpenAiResponseOwnerStore responseOwnerStore = mock(OpenAiResponseOwnerStore.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = account();
        UUID leaseId = UUID.randomUUID();

        when(apiKeyService.authenticate(any()))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.releaseUsage(any(), anyString(), any(Boolean.class), anyString())).thenReturn(Mono.empty());
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountSettingsService.routingPolicy())
            .thenReturn(Mono.just(AccountDtos.RoutingPolicySettings.defaults()));
        when(accountPoolService.selectOpenAiApiKeyAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(admissionStore.acquire(any())).thenReturn(Mono.just(
            new AccountAdmissionStore.AdmissionDecision(
                true,
                leaseId,
                null,
                1L,
                Instant.now().plusSeconds(600)
            )
        ));
        when(admissionStore.markDispatched(leaseId)).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(leaseId)).thenReturn(Mono.empty());
        when(admissionStore.abandon(eq(leaseId), anyString())).thenReturn(Mono.empty());
        when(paymentSettingsService.config()).thenReturn(Mono.just(paymentConfig));
        when(paymentConfig.apiBillingMultiplier()).thenReturn(BigDecimal.ONE);
        when(modelPricingService.billingQuote(anyString(), eq(false), eq(BigDecimal.ONE)))
            .thenReturn(Mono.just(new tech.starryapi.model.BillingQuote(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        when(modelPricingService.cost(anyString(), any(), eq(false), eq(BigDecimal.ONE)))
            .thenReturn(Mono.just(BigDecimal.ONE));
        when(apiKeyService.accountUsage(any(), anyString(), any(), any(Long.class), any(Long.class)))
            .thenReturn(Mono.empty());
        when(accountPoolService.recordSuccess(
            eq(account.getAccountId()),
            any(Long.class),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        OpenAiResponseFactory openAiResponseFactory = spy(new OpenAiResponseFactory(objectMapper));
        ProxyFacade facade = new ProxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
            disabledAccountScheduledTestService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiroUpstreamClient,
            openAiUpstreamClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            openAiResponseFactory,
            new OpenAiResponseStateStore(objectMapper),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            anthropicUpstreamClient,
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            paymentSettingsService,
            modelPricingService,
            statsService,
            admissionStore,
            responseOwnerStore
        );
        return new Fixture(
            facade,
            account,
            leaseId,
            accountPoolService,
            openAiUpstreamClient,
            openAiResponseFactory,
            paymentSettingsService,
            admissionStore,
            apiKeyService,
            modelPricingService
        );
    }

    private JsonNode request() throws Exception {
        return objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
    }

    private ProxyAccountEntity account() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("openai-main");
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setEnabled(true);
        return account;
    }

    private record Fixture(
        ProxyFacade facade,
        ProxyAccountEntity account,
        UUID leaseId,
        AccountPoolService accountPoolService,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiResponseFactory openAiResponseFactory,
        PaymentSettingsService paymentSettingsService,
        AccountAdmissionStore admissionStore,
        ApiKeyService apiKeyService,
        ModelPricingService modelPricingService
    ) {
        void successfulUpstream() {
            when(openAiUpstreamClient.complete(
                eq(account),
                any(JsonNode.class),
                eq("gpt-5")
            )).thenReturn(Mono.just(new PlatformCompletionResult(
                "OK",
                new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0)
            )));
        }
    }
}
