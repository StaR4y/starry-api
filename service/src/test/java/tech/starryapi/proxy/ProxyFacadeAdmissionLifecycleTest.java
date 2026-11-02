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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogCustomization;
import tech.starryapi.model.ModelCatalogProvider;
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
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
        var body = (tools.jackson.databind.node.ObjectNode) request();
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

    /**
     * The default output that a Responses request naming no limit is reserved and sent by is not what the account
     * is held for: admission is asked for the 4,096 in-flight output tokens it always was, which keeps the account's cap on
     * in-flight tokens admitting as many Codex turns as before. A request that names a limit is held for that limit.
     */
    @Test
    void aResponsesRequestThatNamesNoLimitIsAdmittedForTheFourThousandItAlwaysWasAndNotForTheDefault() throws Exception {
        Fixture fixture = fixture();
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));

        fixture.facade().responses(responsesRequest(null), responsesExchange()).block();
        fixture.facade().responses(responsesRequest(2000), responsesExchange()).block();

        ArgumentCaptor<AccountAdmissionStore.AdmissionRequest> asked = ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionRequest.class);
        verify(fixture.admissionStore(), times(2)).acquire(asked.capture());
        assertThat(asked.getAllValues()).extracting(AccountAdmissionStore.AdmissionRequest::reservedOutputTokens)
            .containsExactly(4096L, 2000L);
        // What the request is sent and reserved by is the default of the gateway when it names none (gpt-5 writes 64,000 in
        // the catalog, more than the 16,000 the gateway gives).
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient(), times(2)).completeResponses(eq(fixture.account()), sent.capture(), eq("gpt-5"));
        assertThat(sent.getAllValues()).extracting(body -> body.path("max_output_tokens").longValue()).containsExactly(16_000L, 2000L);
        ArgumentCaptor<BigDecimal> hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService(), times(2)).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getAllValues().get(0)).isGreaterThan(new BigDecimal("16000")).isLessThan(new BigDecimal("18000"));
        assertThat(hold.getAllValues().get(1)).isGreaterThan(new BigDecimal("2000")).isLessThan(new BigDecimal("4000"));
    }

    /**
     * The in-flight tokens of an account are the existing cap on what it takes at once: a Codex turn whose prompt is large
     * must count for the prompt and the 4,096 it always did, whatever the default is.
     */
    @Test
    void theInFlightTokensOfAnAdmittedResponsesRequestDoNotGrowWithTheDefault() throws Exception {
        Fixture fixture = fixture(32_000);
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        var big = (tools.jackson.databind.node.ObjectNode) responsesRequest(null);
        big.put("instructions", "x".repeat(240_000));

        fixture.facade().responses(big, responsesExchange()).block();

        ArgumentCaptor<AccountAdmissionStore.AdmissionRequest> asked = ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionRequest.class);
        verify(fixture.admissionStore()).acquire(asked.capture());
        // 60,000 tokens of prompt and the 4,096 of output: about 64,100 of the 1,000,000 an account holds, not 92,000.
        assertThat(asked.getValue().reservedInputTokens() + asked.getValue().reservedOutputTokens()).isBetween(64_000L, 64_300L);
    }

    /**
     * The default is a setting of the operator's (STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS, 16,000 unless set), and the number
     * goes into the body the source is sent and into the reservation alike, as the default does.
     */
    @Test
    void theDefaultLimitOfAResponsesRequestIsTheOneTheOperatorSetAndIsSentAndReservedAlike() throws Exception {
        Fixture fixture = fixture(32_000);
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));

        fixture.facade().responses(responsesRequest(null), responsesExchange()).block();
        fixture.facade().responses(responsesRequest(2000), responsesExchange()).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient(), times(2)).completeResponses(eq(fixture.account()), sent.capture(), eq("gpt-5"));
        assertThat(sent.getAllValues()).extracting(body -> body.path("max_output_tokens").longValue()).containsExactly(32_000L, 2000L);
        ArgumentCaptor<BigDecimal> hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService(), times(2)).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getAllValues().get(0)).isGreaterThan(new BigDecimal("32000")).isLessThan(new BigDecimal("34000"));
        assertThat(hold.getAllValues().get(1)).isGreaterThan(new BigDecimal("2000")).isLessThan(new BigDecimal("4000"));
        // The other entries are not moved by it: a Chat request that names no limit is still held to 4,096.
        fixture.successfulUpstream();
        fixture.facade().chat(request(), exchange()).block();
        ArgumentCaptor<JsonNode> chat = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient()).complete(any(), chat.capture(), anyString());
        assertThat(chat.getValue().path("max_tokens").longValue()).isEqualTo(4096L);
    }

    /**
     * The model list gives most aliases one maximum output (64,000), above what several of their upstreams hold a request to
     * (gpt-4o 16,384, gpt-4.1 32,768, gpt-4 4,096): an official key refuses a request over that, so a model the price catalog
     * lists less for is not given more than it lists, and the default is held to it.
     */
    @Test
    void theDefaultLimitOfAResponsesRequestIsHeldToWhatThePriceCatalogListsForTheModel() throws Exception {
        Fixture fixture = fixture();
        when(fixture.modelPricingService().maxOutputTokens("gpt-5")).thenReturn(java.util.OptionalInt.of(8_192));
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));

        fixture.facade().responses(responsesRequest(null), responsesExchange()).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient()).completeResponses(eq(fixture.account()), sent.capture(), eq("gpt-5"));
        assertThat(sent.getValue().path("max_output_tokens").longValue()).isEqualTo(8_192L);
        ArgumentCaptor<BigDecimal> hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService()).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getValue()).isGreaterThan(new BigDecimal("8192")).isLessThan(new BigDecimal("10000"));
        ArgumentCaptor<AccountAdmissionStore.AdmissionRequest> asked = ArgumentCaptor.forClass(AccountAdmissionStore.AdmissionRequest.class);
        verify(fixture.admissionStore()).acquire(asked.capture());
        assertThat(asked.getValue().reservedOutputTokens()).isEqualTo(4096L);
    }

    /** The default is the setting where the model writes more, and what the model writes where it writes less. */
    @Test
    void theDefaultLimitIsNeverAboveWhatTheModelWrites() throws Exception {
        Fixture fixture = fixture();
        assertThat(fixture.facade().defaultResponsesOutputLimit("gpt-5.4")).as("the setting, 16,000").isEqualTo(16_000L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(16_000L);

        Fixture raised = fixture(100_000);
        // The model list shows 64,000 for both aliases, which is what they are held to however high the setting is.
        assertThat(raised.facade().defaultResponsesOutputLimit("gpt-5.4")).isEqualTo(64_000L);
        assertThat(raised.facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(64_000L);
        // A catalog that lists more does not lift it ...
        when(raised.modelPricingService().maxOutputTokens(anyString())).thenReturn(java.util.OptionalInt.of(128_000));
        assertThat(raised.facade().defaultResponsesOutputLimit("gpt-5.4")).isEqualTo(64_000L);
        // ... and one that lists less lowers it: the smaller of the two is what the model writes.
        when(raised.modelPricingService().maxOutputTokens("gpt-5.4")).thenReturn(java.util.OptionalInt.of(32_768));
        assertThat(raised.facade().defaultResponsesOutputLimit("gpt-5.4")).isEqualTo(32_768L);
        assertThat(raised.facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(64_000L);
    }

    @Test
    void aModelTheModelListDoesNotKnowKeepsTheFallbackWhateverThePriceCatalogListsAndTheSettingIs() throws Exception {
        Fixture fixture = fixture(100_000);
        when(fixture.modelPricingService().maxOutputTokens(anyString())).thenReturn(java.util.OptionalInt.of(128_000));

        assertThat(fixture.facade().defaultResponsesOutputLimit("a-model-nobody-lists")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("openai-compatible/some-model")).isEqualTo(4096L);
    }

    /**
     * A model the administrator added is listed with the 64,000 every model gets that has no maximum of its own, which says
     * nothing of what its backend holds a request to: it has no maximum the gateway knows, and a request that names no limit
     * is held to the 4,096 it always was (the window the administrator wrote is not a limit on the output either, and a
     * default of 16,000 would be refused by a backend whose window is smaller, whatever the prompt).
     */
    @Test
    void aModelTheAdministratorAddedHasNoMaximumOutputAndKeepsTheFallbackWhateverItsWindowIs() throws Exception {
        Fixture fixture = fixture();
        fixture.modelCatalog().applyCustomizations(List.of(new ModelCatalogCustomization(
            "my-vllm-model", "My model", null, null, null, null, null, null, 8_192, null, true, null)));
        assertThat(fixture.modelCatalog().models()).filteredOn(spec -> spec.id().equals("my-vllm-model"))
            .singleElement().satisfies(spec -> {
                assertThat(spec.maxOutputTokens()).as("what the model list shows is the placeholder").isEqualTo(64_000);
                assertThat(spec.maxInputTokens()).isEqualTo(8_192);
            });

        assertThat(fixture.facade().defaultResponsesOutputLimit("my-vllm-model")).isEqualTo(4096L);
        // The model a request is served as is the one that counts: a client that names the administrator's model and a key whose
        // mapping sends it to a built-in one is served by that one's backend ...
        assertThat(fixture.facade().defaultResponsesOutputLimit("gpt-5.4")).isEqualTo(16_000L);
    }

    /**
     * The console lets the administrator edit a built-in model and point it at their own vendor with a window of their own:
     * the model is then served from the OpenAI-compatible keys, which are the operator's backend, and it keeps the owner and
     * the 64,000 of the built-in. The backend is the one that refuses input plus output over its window, so it is held to the
     * 4,096 it always was like a model the administrator added, and the same built-in unedited still gets the default.
     */
    @Test
    void aBuiltInModelTheAdministratorPointedAtTheirOwnVendorHasNoMaximumOutputEitherAndKeepsTheFallback() throws Exception {
        Fixture fixture = fixture();
        assertThat(fixture.facade().defaultResponsesOutputLimit("gpt-5")).as("as shipped").isEqualTo(16_000L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(16_000L);
        fixture.modelCatalog().applyCustomizations(List.of(
            new ModelCatalogCustomization("gpt-5", "gpt-5", null, "MyVLLM", "Custom", "qwen3-coder-30b",
                "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 32_768, null, true, "#336699"),
            new ModelCatalogCustomization("claude-sonnet-4.5", "claude-sonnet-4.5", null, "MyVLLM", "Custom", "qwen3-coder-30b",
                "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 32_768, null, true, "#336699")));
        assertThat(fixture.modelCatalog().models()).filteredOn(spec -> spec.id().equals("gpt-5")).singleElement().satisfies(spec -> {
            assertThat(spec.provider()).isEqualTo("MyVLLM");
            assertThat(spec.ownedBy()).as("the owner of the built-in is kept").isNotEqualTo(ModelCatalogProvider.ADMIN_OWNER);
            assertThat(spec.maxOutputTokens()).as("and so is its figure").isEqualTo(64_000);
        });

        assertThat(fixture.facade().defaultResponsesOutputLimit("gpt-5")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(4096L);
        // The edit is of those models only, and a raised setting does not lift it.
        assertThat(fixture.facade().defaultResponsesOutputLimit("gpt-5.4")).isEqualTo(16_000L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("claude-opus-4.1")).isEqualTo(16_000L);
        Fixture raised = fixture(100_000);
        raised.modelCatalog().applyCustomizations(List.of(
            new ModelCatalogCustomization("gpt-5", "gpt-5", null, "MyVLLM", "Custom", "qwen3-coder-30b",
                "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 32_768, null, true, "#336699")));
        when(raised.modelPricingService().maxOutputTokens(anyString())).thenReturn(java.util.OptionalInt.of(128_000));
        assertThat(raised.facade().defaultResponsesOutputLimit("gpt-5")).isEqualTo(4096L);
    }

    /** No built-in model that ships is served from the OpenAI-compatible keys (their providers are first-class), so none loses the default. */
    @Test
    void noBuiltInModelThatShipsIsServedByTheOperatorsOwnBackend() throws Exception {
        Fixture fixture = fixture();

        assertThat(fixture.modelCatalog().models()).isNotEmpty().allSatisfy(spec -> assertThat(
            AccountPlatforms.isCustomOpenAiCompatibleProvider(spec.provider(), AccountPlatforms.OPENAI_API_KEY_AUTH))
            .as("the provider of %s", spec.id()).isFalse());
    }

    /**
     * The other direction: a mapping rule sends a built-in name (Codex's own) to the administrator's model, or to one the
     * catalog does not list. The backend is the one the mapping names, whatever the client wrote, so it is not sent the
     * default that a backend with a window of 8,192 answers with a 400 to every request that names no limit.
     */
    @Test
    void aMappingThatSendsABuiltInModelToAModelTheAdministratorAddedKeepsTheFallbackWhateverTheClientNamed() throws Exception {
        Fixture fixture = fixture();
        fixture.modelCatalog().applyCustomizations(List.of(new ModelCatalogCustomization(
            "my-vllm-model", "My model", null, null, null, null, null, null, 8_192, null, true, null)));

        assertThat(fixture.facade().defaultResponsesOutputLimit("my-vllm-model")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("openai-compatible/some-model")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("starry/my-vllm-model")).isEqualTo(4096L);
    }

    @Test
    void aResponsesRequestThatAMappingSendsToAModelTheAdministratorAddedIsSentAndReservedByTheFallback() throws Exception {
        Fixture fixture = fixture();
        fixture.modelCatalog().applyCustomizations(List.of(new ModelCatalogCustomization(
            "my-vllm-model", "My model", null, null, null, null, null, null, 8_192, null, true, null)));
        when(fixture.modelMappingService().apply("gpt-5", "key-1")).thenReturn("my-vllm-model");
        // A model the administrator added is served by the OpenAI-compatible keys.
        when(fixture.accountPoolService().selectOpenAiCompatibleAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(fixture.account()));
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), eq("my-vllm-model")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));

        fixture.facade().responses(responsesRequest(null), responsesExchange()).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient()).completeResponses(eq(fixture.account()), sent.capture(), eq("my-vllm-model"));
        assertThat(sent.getValue().path("max_output_tokens").longValue()).as("the backend of the administrator's model").isEqualTo(4096L);
        ArgumentCaptor<BigDecimal> hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService()).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getValue()).isGreaterThan(new BigDecimal("4096")).isLessThan(new BigDecimal("6000"));
    }

    /**
     * The documented way to reach the operator's own backend is the {@code openai-compatible/} prefix, and a model registered under
     * it has the name of a built-in after the prefix (a vLLM that serves {@code gpt-4o-mini}, a common compatibility name). The
     * model list finds a model by its compact local id, built-ins first, so the spec it finds for the prefixed name is the built-in's,
     * owned by the gateway and priced by the catalog; but the request goes to the OpenAI-compatible keys, which are the operator's
     * backend, and is held to the 4,096 as any backend of the operator is: the window the administrator wrote
     * (32,768 here) would not hold the 16,384 the catalog lists for the built-in beside a prompt of 20,000 tokens.
     */
    @Test
    void aModelOfTheOperatorsBackendReachedByThePrefixIsHeldToTheFallbackWhateverBuiltInHasItsName() throws Exception {
        Fixture fixture = fixture(100_000);
        when(fixture.modelPricingService().maxOutputTokens(anyString())).thenReturn(java.util.OptionalInt.of(16_384));
        fixture.modelCatalog().applyCustomizations(List.of(new ModelCatalogCustomization(
            "openai-compatible/gpt-4o-mini", "openai-compatible/gpt-4o-mini", null, "OpenAI", null, null,
            "OPENAI_CHAT_COMPLETIONS", null, 32_768, null, true, null)));

        assertThat(fixture.facade().defaultResponsesOutputLimit("openai-compatible/gpt-4o-mini")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("openai-compatible/gpt-5")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("openai-compatible/deepseek-v4-flash")).isEqualTo(4096L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("starry/openai-compatible/gpt-4o-mini")).isEqualTo(4096L);
        // The built-in itself is the gateway's, and keeps what the catalog lists for it.
        assertThat(fixture.facade().defaultResponsesOutputLimit("gpt-4o-mini")).isEqualTo(16_384L);

        when(fixture.modelMappingService().apply("gpt-5", "key-1")).thenReturn("openai-compatible/gpt-4o-mini");
        when(fixture.accountPoolService().selectOpenAiCompatibleAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(fixture.account()));
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), anyString()))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));

        fixture.facade().responses(responsesRequest(null), responsesExchange()).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient()).completeResponses(eq(fixture.account()), sent.capture(), anyString());
        assertThat(sent.getValue().path("max_output_tokens").longValue()).as("the backend of the operator").isEqualTo(4096L);
        ArgumentCaptor<BigDecimal> hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService()).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getValue()).isGreaterThan(new BigDecimal("4096")).isLessThan(new BigDecimal("6000"));
    }

    /** A Codex-style request for a built-in model the administrator pointed at their own vendor is sent and reserved by the fallback. */
    @Test
    void aResponsesRequestForABuiltInModelTheAdministratorPointedAtTheirOwnVendorIsSentAndReservedByTheFallback() throws Exception {
        Fixture fixture = fixture();
        fixture.modelCatalog().applyCustomizations(List.of(
            new ModelCatalogCustomization("gpt-5", "gpt-5", null, "MyVLLM", "Custom", "qwen3-coder-30b",
                "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 32_768, null, true, "#336699")));
        when(fixture.accountPoolService().selectOpenAiCompatibleAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(fixture.account()));
        when(fixture.openAiUpstreamClient().completeResponses(eq(fixture.account()), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));

        fixture.facade().responses(responsesRequest(null), responsesExchange()).block();

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.openAiUpstreamClient()).completeResponses(eq(fixture.account()), sent.capture(), eq("gpt-5"));
        assertThat(sent.getValue().path("max_output_tokens").longValue()).as("the backend of the administrator").isEqualTo(4096L);
        ArgumentCaptor<BigDecimal> hold = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.apiKeyService()).reserveUsage(any(), anyString(), hold.capture(), anyString());
        assertThat(hold.getValue()).isGreaterThan(new BigDecimal("4096")).isLessThan(new BigDecimal("6000"));
    }

    /**
     * The model list shows 384,000 for the DeepSeek models and the price catalog that ships with the release 8,192: the model is held
     * to the smaller of the two, and Claude, for which both catalogs list 64,000, is given the default. The figure is the catalog's as
     * it is now, and the catalog is refreshed from the remote repository by default: the refreshed one lists 393,216 for the DeepSeek
     * v4 models, which holds nothing back, so a deployment that refreshes gives them the default.
     */
    @Test
    void deepSeekV4IsHeldToTheSmallerOfTheTwoCatalogsAndClaudeGetsTheDefault() throws Exception {
        Fixture fixture = fixture(100_000);
        when(fixture.modelPricingService().maxOutputTokens("deepseek-v4-flash")).thenReturn(java.util.OptionalInt.of(8_192));
        when(fixture.modelPricingService().maxOutputTokens("deepseek-v4-pro")).thenReturn(java.util.OptionalInt.of(8_192));
        when(fixture.modelPricingService().maxOutputTokens("claude-sonnet-4.5")).thenReturn(java.util.OptionalInt.of(64_000));

        assertThat(fixture.modelCatalog().models()).filteredOn(spec -> spec.id().equals("deepseek-v4-flash"))
            .singleElement().satisfies(spec -> assertThat(spec.maxOutputTokens()).isEqualTo(384_000));
        assertThat(fixture.facade().defaultResponsesOutputLimit("deepseek-v4-flash")).isEqualTo(8_192L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("deepseek-v4-pro")).isEqualTo(8_192L);
        assertThat(fixture.facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(64_000L);
        assertThat(fixture().facade().defaultResponsesOutputLimit("claude-sonnet-4.5")).isEqualTo(16_000L);

        // The catalog after a refresh lists what the model writes, which the default is below.
        Fixture refreshed = fixture();
        when(refreshed.modelPricingService().maxOutputTokens("deepseek-v4-flash")).thenReturn(java.util.OptionalInt.of(393_216));
        when(refreshed.modelPricingService().maxOutputTokens("deepseek-v4-pro")).thenReturn(java.util.OptionalInt.of(393_216));
        assertThat(refreshed.facade().defaultResponsesOutputLimit("deepseek-v4-flash")).isEqualTo(16_000L);
        assertThat(refreshed.facade().defaultResponsesOutputLimit("deepseek-v4-pro")).isEqualTo(16_000L);
    }

    private JsonNode responsesRequest(Integer limit) throws Exception {
        var body = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree("{\"model\": \"gpt-5\", \"input\": \"hi\"}");
        if (limit != null) {
            body.put("max_output_tokens", limit);
        }
        return body;
    }

    private MockServerWebExchange responsesExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses"));
    }

    private Fixture fixture() {
        return fixture(ProxyProperties.DEFAULT_RESPONSES_OUTPUT_TOKENS);
    }

    /** A facade whose operator set the default output limit of a Responses request that names none to {@code defaultOutputTokens}. */
    private Fixture fixture(long defaultOutputTokens) {
        ProxyProperties properties = new ProxyProperties();
        properties.setResponsesDefaultOutputTokens(defaultOutputTokens);
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
        when(responseOwnerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());

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
            responseOwnerStore,
            TierRuntime.disabled(),
            null,
            null,
            properties
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
            modelPricingService,
            modelCatalog,
            modelMappingService
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
        ModelPricingService modelPricingService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService
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
