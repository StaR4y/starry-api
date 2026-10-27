package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.EgressFailure;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformToolUse;
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
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayAnswerWithheldException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.RelayNotSentException;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Limits;
import tech.starryapi.proxy.UpstreamFailureClassifier.Classification;
import tech.starryapi.proxy.UpstreamFailureClassifier.RelayFailure;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierRetryPolicy;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the cross-protocol dispatcher hands the rest of a request (design 7.5, 7.7, 7.8): the request it writes, the
 * answer it makes of what the relay said, the ids it mints, and, above all, that nothing it throws leaves the
 * reservation of the request for review or lets the request be generated twice. The relay client is a stand-in for the
 * rules of the dispatcher itself, and a real one against a relay on the loopback address for what a failure looks like on
 * the wire.
 */
class XpDispatcherTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LIMIT = Duration.ofSeconds(30);
    private static final Duration WAIT = Duration.ofSeconds(40);
    private static final Limits LIMITS = new Limits(100_000, 4_096);
    private static final String WIRE_MODEL = "Claude-Sonnet-4.5-Relay";
    private static final String RELAY_WORDS = "relay.internal.example.com sk-secret-0123";

    private final OpenAiChatRelayClient client = mock(OpenAiChatRelayClient.class);
    private final XpDispatcher dispatcher = new XpDispatcher(client);
    private final ProxyAccountEntity account = relayAccount("http://127.0.0.1:1");
    private final List<AutoCloseable> open = new ArrayList<>();

    /** Only for the question what a tiered request's reservation does with a failure: {@code tierDefinitive}. */
    private final ProxyFacade facade = new ProxyFacade(
        mock(ApiKeyService.class),
        mock(AccountPoolService.class),
        mock(AccountAutoSuspendService.class),
        mock(AccountSettingsService.class),
        mock(DisabledAccountScheduledTestService.class),
        new DefaultModelCatalogProvider(),
        mock(ModelMappingService.class),
        mock(RateLimiterService.class),
        new KiroPayloadFactory(JSON, new DefaultModelCatalogProvider()),
        mock(KiroUpstreamClient.class),
        mock(OpenAiUpstreamClient.class),
        new OpenAiRequestAdapter(JSON, new DefaultModelCatalogProvider()),
        new OpenAiResponseFactory(JSON),
        new OpenAiResponseStateStore(JSON),
        new AnthropicRequestAdapter(JSON, new DefaultModelCatalogProvider()),
        new AnthropicResponseFactory(JSON),
        mock(AnthropicUpstreamClient.class),
        new GeminiRequestAdapter(JSON),
        new GeminiResponseFactory(JSON),
        mock(PaymentSettingsService.class),
        mock(ModelPricingService.class),
        mock(StatsService.class),
        mock(AccountAdmissionStore.class),
        mock(OpenAiResponseOwnerStore.class),
        TierRuntime.disabled()
    );

    @AfterEach
    void closeTheRelays() throws Exception {
        for (AutoCloseable relay : open) {
            relay.close();
        }
    }

    // --- the request ---

    @Test
    void writesTheWireModelFirstIntoACopyAndLeavesTheSharedBodyAlone() {
        ObjectNode shared = JSON.createObjectNode();
        shared.putArray("messages").addObject().put("role", "user").put("content", "Hi");
        shared.put("temperature", 0.25);
        shared.put("stream", false);
        JsonNode before = shared.deepCopy();
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        when(client.complete(eq(account), sent.capture(), eq(LIMIT))).thenReturn(Mono.just(answer("ok")));

        dispatcher.dispatch(account, relay(Entry.MESSAGES, shared), LIMITS, LIMIT).block(WAIT);

        JsonNode body = sent.getValue();
        assertThat(body).isNotSameAs(shared);
        assertThat(body.propertyNames()).containsExactly("model", "messages", "temperature", "stream");
        assertThat(body.path("model").asString()).isEqualTo(WIRE_MODEL);
        assertThat(body.get("messages")).isSameAs(shared.get("messages"));
        assertThat(shared).isEqualTo(before);
        assertThat(shared.has("model")).isFalse();
    }

    @Test
    void aModelNameThatTheSharedBodyAlreadyHasIsReplacedByTheWireModel() {
        ObjectNode shared = JSON.createObjectNode();
        shared.put("temperature", 0.25);
        shared.put("model", "what-the-client-asked-for");
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        when(client.complete(any(), sent.capture(), any())).thenReturn(Mono.just(answer("ok")));

        dispatcher.dispatch(account, relay(Entry.CHAT, shared), LIMITS, LIMIT).block(WAIT);

        assertThat(sent.getValue().propertyNames()).containsExactly("model", "temperature");
        assertThat(sent.getValue().path("model").asString()).isEqualTo(WIRE_MODEL);
        assertThat(shared.path("model").asString()).isEqualTo("what-the-client-asked-for");
    }

    @Test
    void everySubscriptionIsOneRequestAndNothingIsSentBeforeIt() {
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(answer("ok")));
        Mono<PlatformCompletionResult> attempt = dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT);

        verifyNoInteractions(client);
        attempt.block(WAIT);
        attempt.block(WAIT);

        verify(client, times(2)).complete(eq(account), any(), eq(LIMIT));
    }

    // --- the answer ---

    @Test
    void theAnswerCarriesTheTextTheStopReasonTheUsageAndWhatWasLeftOut() throws Exception {
        Completion completion = new Completion("The answer", "Some thinking", List.of(), StopReason.MAX_TOKENS,
            JSON.readTree("{\"prompt_tokens\":1000,\"completion_tokens\":50,\"prompt_tokens_details\":{\"cached_tokens\":400}}"));
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(completion));

        PlatformCompletionResult result = dispatcher.dispatch(
            account, relay(Entry.CHAT, body(), List.of("cache_control", "image")), LIMITS, LIMIT).block(WAIT);

        assertThat(result.content()).isEqualTo("The answer");
        assertThat(result.reasoningContent()).isEqualTo("Some thinking");
        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(result.usageSource()).isEqualTo(UsageSource.REPORTED);
        assertThat(result.degraded()).containsExactly("cache_control", "image");
        assertThat(result.usage().inputTokens()).isEqualTo(1000);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(400);
        assertThat(result.toolUses()).isEmpty();
    }

    @Test
    void theUsageIsReadAsTheMembersCaliberSaysAndBoundedByWhatWasReserved() throws Exception {
        // 2,000 prompt tokens and 6,000 of cache read that the prompt does not contain: 8,000 in all.
        Completion completion = new Completion("ok", "", List.of(), StopReason.END_TURN,
            JSON.readTree("{\"prompt_tokens\":2000,\"completion_tokens\":9000,\"cache_read_input_tokens\":6000}"));
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(completion));

        PlatformCompletionResult separate = dispatcher.dispatch(
            account, relay(Entry.MESSAGES, body(), member(false, true), List.of()), LIMITS, LIMIT).block(WAIT);
        PlatformCompletionResult contained = dispatcher.dispatch(
            account, relay(Entry.MESSAGES, body(), member(true, true), List.of()), LIMITS, LIMIT).block(WAIT);

        assertThat(separate.usage().inputTokens()).isEqualTo(8000);
        assertThat(separate.usage().cacheReadTokens()).isEqualTo(6000);
        // The output is cut to the limit that was forwarded, and the answer says so.
        assertThat(separate.usage().outputTokens()).isEqualTo(4096);
        assertThat(separate.usageSource()).isEqualTo(UsageSource.CLAMPED);
        // Contained: the 6,000 of cache read are part of the 2,000, which they exceed, so they are cut to fit.
        assertThat(contained.usage().inputTokens()).isEqualTo(2000);
        assertThat(contained.usage().cacheReadTokens()).isEqualTo(2000);
    }

    @Test
    void anAnswerWithoutUsageIsBilledOnAnEstimateAndSaysSo() throws Exception {
        Completion completion = new Completion("An answer of some length", "", List.of(), StopReason.END_TURN, JSON.createObjectNode());
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(completion));

        PlatformCompletionResult result = dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT).block(WAIT);

        assertThat(result.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(result.usage().inputTokens()).isGreaterThan(0);
        assertThat(result.usage().outputTokens()).isGreaterThan(0);
    }

    @Test
    void anAnswerOfReasoningAloneIsNoAnswerToAMessagesRequestBecauseThereIsNowhereToPutIt() {
        Completion thought = new Completion("", "Let me think about it", List.of(), StopReason.END_TURN,
            JSON.createObjectNode().put("prompt_tokens", 50).put("completion_tokens", 40));
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(thought));

        // Rendered, it would be an empty text block and end_turn: Claude Code stops, and the user pays for the thinking.
        Throwable thrown = failureOf(dispatcher.dispatch(account, relay(Entry.MESSAGES, body()), LIMITS, LIMIT));

        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        assertThat(UpstreamFailureClassifier.classify(thrown, account, relay(Entry.MESSAGES, body())).interrupted()).isTrue();
        // Chat has a place for it, and an answer that stopped for length says so.
        PlatformCompletionResult chat = dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT).block(WAIT);
        assertThat(chat.reasoningContent()).isEqualTo("Let me think about it");
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(
            new Completion("", "Still thinking", List.of(), StopReason.MAX_TOKENS, JSON.createObjectNode().put("completion_tokens", 40))));
        assertThat(dispatcher.dispatch(account, relay(Entry.MESSAGES, body()), LIMITS, LIMIT).block(WAIT).stopReason())
            .isEqualTo(StopReason.MAX_TOKENS);
    }

    // --- the answers a Responses client cannot be told ---

    private static final JsonNode USAGE_OF_A_CUT_ANSWER = JSON.createObjectNode().put("prompt_tokens", 1300).put("completion_tokens", 4096);

    private static Completion cutAnswer(String text, String reasoning, List<PlatformToolUse> calls, boolean callCut) {
        return new Completion(text, reasoning, calls, StopReason.MAX_TOKENS, USAGE_OF_A_CUT_ANSWER, "length", callCut);
    }

    /** The relay that is held to the default of the reservation, by a request that named no limit. */
    private Mono<PlatformCompletionResult> dispatchedToCodex(Completion completion) {
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(completion));
        return dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT, true);
    }

    /**
     * The answer is held back: the request ends there as an interruption does (nobody else is asked, the reservation is
     * released without charge, the relay is kept from the session), and it is no failure of the relay.
     */
    private void assertInterruptedAndNotBilled(Throwable thrown) {
        assertThat(thrown).isInstanceOf(RelayAnswerWithheldException.class).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        UpstreamFailureClassifier.Classification classification =
            UpstreamFailureClassifier.classify(thrown, account, relay(Entry.RESPONSES, body()));
        assertThat(classification.interrupted()).isTrue();
        assertThat(classification.withheld()).isTrue();
        assertThat(classification.retryable()).isFalse();
        assertThat(thrown).hasNoCause().message().doesNotContain(RELAY_WORDS);
    }

    /**
     * The 4,096 tokens of the reservation are the gateway's own bound (Codex accounts drop it; Kiro, official keys and Anthropic
     * keys are sent it too). An answer that lost its call to it was not what the model meant, and told as incomplete it would be
     * asked for five more times.
     */
    @Test
    void anAnswerCutInsideItsOnlyCallAtTheGatewaysBoundIsAnInterruptionOfTheResponsesEntry() {
        assertInterruptedAndNotBilled(failureOf(dispatchedToCodex(cutAnswer("", "", List.of(), true))));
    }

    @Test
    void anAnswerCutInsideACallAfterSomeTextOrAWholeCallIsAnInterruptionToo() {
        assertInterruptedAndNotBilled(failureOf(dispatchedToCodex(cutAnswer("I will run it.", "", List.of(), true))));
        assertInterruptedAndNotBilled(failureOf(dispatchedToCodex(cutAnswer("I will run both.", "", List.of(call("call_0", "Read")), true))));
    }

    @Test
    void anAnswerOfReasoningAloneCutAtTheGatewaysBoundIsAnInterruptionOfTheResponsesEntry() {
        assertInterruptedAndNotBilled(failureOf(dispatchedToCodex(cutAnswer("", "thinking at length", List.of(), false))));
        assertInterruptedAndNotBilled(failureOf(dispatchedToCodex(cutAnswer("  \n", "thinking at length", List.of(), false))));
    }

    @Test
    void anAnswerCutAtTheGatewaysBoundThatCanBeToldIsToldWithTheStopReasonForTheFacadeToRender() {
        PlatformCompletionResult text = dispatchedToCodex(cutAnswer("The answer begins and then", "", List.of(), false)).block(WAIT);
        PlatformCompletionResult call = dispatchedToCodex(cutAnswer("", "", List.of(call("call_0", "Read")), false)).block(WAIT);

        assertThat(text.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(text.content()).isEqualTo("The answer begins and then");
        assertThat(call.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(call.toolUses()).hasSize(1);
    }

    @Test
    void anAnswerTheRelaysFilterStoppedIsAnInterruptionOfTheResponsesEntryWhateverTheLimitIs() {
        Completion filtered = new Completion("I cannot continue with", "", List.of(), StopReason.CONTENT_FILTER,
            JSON.createObjectNode().put("prompt_tokens", 1200).put("completion_tokens", 6), "content_filter");
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(filtered));

        assertInterruptedAndNotBilled(failureOf(dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT, true)));
        assertInterruptedAndNotBilled(failureOf(dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT, false)));
        assertInterruptedAndNotBilled(failureOf(dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT)));
    }

    /** A limit the client wrote is its own: an answer that reached it is the incomplete one that client asked for. */
    @Test
    void anAnswerCutAtTheLimitTheClientNamedIsToldWhateverIsLostToIt() {
        for (Completion cut : List.of(
            cutAnswer("", "", List.of(), true), cutAnswer("", "thinking at length", List.of(), false), cutAnswer("text", "", List.of(), true))) {
            when(client.complete(any(), any(), any())).thenReturn(Mono.just(cut));

            PlatformCompletionResult result = dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT, false).block(WAIT);

            assertThat(result.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        }
    }

    /** The other entries say what a cut is in their own words (a stop reason that the client handles); nothing changes for them. */
    @Test
    void theOtherEntriesAreToldTheseAnswersAsTheyWere() {
        Completion cut = cutAnswer("", "", List.of(), true);
        Completion filtered = new Completion("I cannot continue with", "", List.of(), StopReason.CONTENT_FILTER, USAGE_OF_A_CUT_ANSWER, "content_filter");
        for (Entry entry : List.of(Entry.CHAT, Entry.MESSAGES, Entry.GEMINI)) {
            when(client.complete(any(), any(), any())).thenReturn(Mono.just(cut));
            assertThat(dispatcher.dispatch(account, relay(entry, body()), LIMITS, LIMIT, true).block(WAIT).stopReason())
                .as(entry.name()).isEqualTo(StopReason.MAX_TOKENS);
            when(client.complete(any(), any(), any())).thenReturn(Mono.just(filtered));
            assertThat(dispatcher.dispatch(account, relay(entry, body()), LIMITS, LIMIT, true).block(WAIT).stopReason())
                .as(entry.name()).isEqualTo(StopReason.CONTENT_FILTER);
        }
    }

    // --- the tool calls ---

    @Test
    void everyToolCallIsGivenAnIdOfTheGatewaysAndTheRelaysOwnIsNotKept() {
        Completion completion = withCalls(call("call_0", "Read"), call("call_0", "Bash"), call("", "Grep"));
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(completion));

        PlatformCompletionResult messages = dispatcher.dispatch(account, relay(Entry.MESSAGES, body()), LIMITS, LIMIT).block(WAIT);
        PlatformCompletionResult chat = dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT).block(WAIT);
        PlatformCompletionResult responses = dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT).block(WAIT);
        PlatformCompletionResult gemini = dispatcher.dispatch(account, relay(Entry.GEMINI, body()), LIMITS, LIMIT).block(WAIT);

        assertThat(messages.toolUses()).extracting(PlatformToolUse::id).allMatch(id -> id.matches("toolu_[0-9A-Za-z]{24}"));
        for (PlatformCompletionResult result : List.of(chat, responses, gemini)) {
            assertThat(result.toolUses()).extracting(PlatformToolUse::id).allMatch(id -> id.matches("call_[0-9A-Za-z]{24}"));
        }
        // Names, arguments and types are the relay's, in the order it made them.
        assertThat(messages.toolUses()).extracting(PlatformToolUse::name).containsExactly("Read", "Bash", "Grep");
        assertThat(messages.toolUses().get(0).input().path("path").asString()).isEqualTo("a");
        assertThat(messages.toolUses().get(0).rawInput()).isEqualTo("{\"path\":\"a\"}");
        assertThat(messages.toolUses().get(0).type()).isEqualTo("function_call");
        assertThat(messages.toolUses()).extracting(PlatformToolUse::id).doesNotContain("call_0", "");
        assertThat(messages.stopReason()).isEqualTo(StopReason.TOOL_USE);
        // The raw item is the Chat shape of the relay's call, with the relay's id: no renderer is to be given it.
        for (PlatformCompletionResult result : List.of(messages, chat, responses, gemini)) {
            assertThat(result.toolUses()).extracting(PlatformToolUse::rawOutputItem).containsOnlyNulls();
        }
    }

    @Test
    void aResponsesItemOfARelaysToolCallHasNeitherTheRelaysIdNorTheShapeOfItsCall() throws Exception {
        // Not "call_0": one minted id in 62 begins with it. One with two underscores is in no id the gateway mints.
        String relayId = "relay_call_0";
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(withCalls(call(relayId, "Read"), call(relayId, "Bash"))));

        PlatformCompletionResult result = dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT).block(WAIT);
        JsonNode rendered = new OpenAiResponseFactory(JSON).responses("claude-sonnet-4.5", result, null);

        JsonNode items = rendered.path("output");
        assertThat(items.toString()).doesNotContain(relayId);
        int calls = 0;
        for (JsonNode item : items) {
            if (!"function_call".equals(item.path("type").asString())) {
                continue;
            }
            calls++;
            assertThat(item.has("function")).as("the Chat shape of the relay's call").isFalse();
            assertThat(item.path("id").asString()).startsWith("fc_");
            assertThat(item.path("call_id").asString()).matches("call_[0-9A-Za-z]{24}");
            assertThat(item.path("arguments").asString()).isEqualTo("{\"path\":\"a\"}");
        }
        assertThat(calls).isEqualTo(2);
    }

    private static final String PATCH = "*** Begin Patch\n*** Add File: a.txt\n+hello\n*** End Patch\n";

    @Test
    void aCallOfTheEditingToolOnTheResponsesEntryIsTheCustomToolCallCodexDeclaredItAsWithThePatchForItsInput() throws Exception {
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(withCalls(patchCall("relay_call_0", patchArguments(PATCH)))));

        PlatformCompletionResult result = dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT).block(WAIT);

        PlatformToolUse use = result.toolUses().getFirst();
        assertThat(use.type()).isEqualTo("custom_tool_call");
        assertThat(use.name()).isEqualTo("apply_patch");
        assertThat(use.rawInput()).isEqualTo(PATCH);
        assertThat(use.id()).matches("call_[0-9A-Za-z]{24}");
        assertThat(use.rawOutputItem()).as("the relay's own item").isNull();
        // What Codex is given: a custom tool call under the minted id, whose input is the patch and not the JSON of an argument.
        JsonNode item = new OpenAiResponseFactory(JSON).responses("claude-sonnet-4.5", result, null).path("output").get(0);
        assertThat(item.path("type").asString()).isEqualTo("custom_tool_call");
        assertThat(item.path("name").asString()).isEqualTo("apply_patch");
        assertThat(item.path("call_id").asString()).isEqualTo(use.id());
        assertThat(item.path("id").asString()).startsWith("ctc_");
        assertThat(item.path("input").asString()).isEqualTo(PATCH);
        assertThat(item.has("arguments")).isFalse();
        assertThat(item.toString()).doesNotContain("relay_call_0");
    }

    @Test
    void thePatchIsTakenOutOfWhateverTheModelWroteAroundIt() {
        // Words after the patch, which models add, are not part of it; so is a patch the model gave under the name of another argument.
        String wordy = PATCH.stripTrailing() + "\nThat is the change.";
        when(client.complete(any(), any(), any())).thenReturn(
            Mono.just(withCalls(patchCall("a", patchArguments(wordy)), patchCall("b", "{\"input\":" + JSON.valueToTree(PATCH) + "}"),
                patchCall("c", "{}"))));

        PlatformCompletionResult result = dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT).block(WAIT);

        assertThat(result.toolUses()).extracting(PlatformToolUse::rawInput).containsExactly(PATCH, PATCH, "");
        assertThat(result.toolUses()).extracting(PlatformToolUse::type).containsOnly("custom_tool_call");
    }

    @Test
    void theEditingToolIsAFunctionCallOnTheEntriesThatNeverDeclaredItAsACustomTool() {
        PlatformToolUse call = patchCall("relay_call_0", patchArguments(PATCH));
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(withCalls(call)));

        for (Entry entry : List.of(Entry.CHAT, Entry.MESSAGES)) {
            PlatformToolUse use = dispatcher.dispatch(account, relay(entry, body()), LIMITS, LIMIT).block(WAIT).toolUses().getFirst();

            // A client of these entries has a function of that name, called with the arguments the model wrote.
            assertThat(use.type()).as(entry.name()).isEqualTo("function_call");
            assertThat(use.rawInput()).as(entry.name()).isEqualTo(patchArguments(PATCH));
        }
    }

    @Test
    void aCallOfAnotherToolOnTheResponsesEntryIsWhatTheRelayMadeOfIt() {
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(withCalls(call("relay_call_0", "exec_command"))));

        PlatformToolUse use = dispatcher.dispatch(account, relay(Entry.RESPONSES, body()), LIMITS, LIMIT).block(WAIT).toolUses().getFirst();

        assertThat(use.type()).isEqualTo("function_call");
        assertThat(use.rawInput()).isEqualTo("{\"path\":\"a\"}");
        assertThat(use.input()).isEqualTo(JSON.createObjectNode().put("path", "a"));
        assertThat(use.rawOutputItem()).isNull();
    }

    @Test
    void mintedIdsAreUniqueInAnAnswerAndAcrossAnswers() {
        Completion completion = withCalls(call("call_0", "Read"), call("call_0", "Read"), call("call_0", "Read"));
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(completion));
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            PlatformCompletionResult result = dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT).block(WAIT);
            result.toolUses().forEach(use -> assertThat(seen.add(use.id())).as(use.id()).isTrue());
        }

        assertThat(seen).hasSize(300);
    }

    @Test
    void anIdThatWouldRepeatWithinAnAnswerIsDrawnAgain() {
        // 24 draws per id: the generator gives the same id twice, and then a different one.
        AtomicInteger draws = new AtomicInteger();
        RandomGenerator repeating = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return draws.getAndIncrement() < 48 ? 0 : 1;
            }
        };
        XpDispatcher stubborn = new XpDispatcher(client, repeating);
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(withCalls(call("a", "Read"), call("b", "Read"))));

        PlatformCompletionResult result = stubborn.dispatch(account, relay(Entry.MESSAGES, body()), LIMITS, LIMIT).block(WAIT);

        assertThat(result.toolUses()).extracting(PlatformToolUse::id)
            .containsExactly("toolu_" + "0".repeat(24), "toolu_" + "1".repeat(24));
    }

    // --- a failure never leaves the reservation for review ---

    @Test
    void whatTheRelayClientRaisesAsAnUpstreamFailureIsKeptWhenItIsAlreadyReleasedAsUnbilled() {
        for (int status : List.of(400, 401, 402, 403, 404, 422, 429)) {
            UpstreamException relayError = new UpstreamException(status, "Source error " + status, status == 402 || status == 429, "7");
            Throwable thrown = failureOf(dispatching(Mono.error(relayError)));
            assertThat(thrown).as("status %s", status).isSameAs(relayError);
            assertThat(facade.tierDefinitive(thrown)).as("status %s", status).isTrue();
        }
        for (int status : List.of(500, 502, 503, 529)) {
            UpstreamException relayError = new UpstreamException(status, "Source error " + status, true);
            assertThat(failureOf(dispatching(Mono.error(relayError)))).isSameAs(relayError);
        }
    }

    @Test
    void everyStatusARelayCanAnswerWithIsReleasedWithoutAReview() {
        for (int status = 400; status <= 599; status++) {
            for (boolean recoverable : List.of(true, false)) {
                UpstreamException relayError = new UpstreamException(status, "Source error " + status + " (server_error)", recoverable);

                Throwable thrown = failureOf(dispatching(Mono.error(relayError)));

                String what = "status " + status + " recoverable=" + recoverable;
                assertThat(facade.tierDefinitive(thrown)).as(what).isTrue();
                assertThat(thrown).isInstanceOf(UpstreamException.class);
                if (RelayErrorMapper.isTimeoutStatus(status) || (status >= 500 && !recoverable)) {
                    // Not an answer of a provider (or a timeout, which the relay goes on from): whether it generated is not known.
                    assertThat(thrown).as(what).isInstanceOf(RelayAbortedAfterDispatchException.class);
                    assertThat(TierRetryPolicy.notStarted(thrown)).as(what).isFalse();
                } else {
                    // A refusal of the relay is no interruption: nothing is said to have been generated.
                    assertThat(thrown).as(what).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
                    assertThat(TierRetryPolicy.notStarted(thrown)).as(what).isTrue();
                    assertThat(thrown.getMessage()).as(what).isEqualTo(relayError.getMessage());
                }
            }
        }
    }

    @Test
    void aStatusThatWouldBeLeftForReviewBecomesTheStatusOfTheSameKindThatIsNot() {
        assertThat(statusOf(409, false)).isEqualTo(502);
        assertThat(statusOf(501, true)).isEqualTo(503);
        assertThat(statusOf(500, true)).isEqualTo(500);
        assertThat(statusOf(502, true)).isEqualTo(502);
        assertThat(statusOf(413, false)).isEqualTo(400);
        assertThat(statusOf(405, false)).isEqualTo(400);
        assertThat(statusOf(431, false)).isEqualTo(400);
        assertThat(statusOf(418, false)).isEqualTo(400);
        // The retry rules see the same kind of failure as before: the relay is tried elsewhere, or the request is refused.
        for (int status : List.of(409, 501)) {
            Throwable thrown = failureOf(dispatching(Mono.error(new UpstreamException(status, "Source error " + status, true))));
            Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
            assertThat(classification.retryable()).as("status %s", status).isTrue();
            assertThat(classification.crossAccountRetryAllowed()).isTrue();
            assertThat(classification.interrupted()).isFalse();
        }
        Classification refusal = UpstreamFailureClassifier.classify(
            failureOf(dispatching(Mono.error(new UpstreamException(413, "Source error 413", false)))), account, relay(Entry.CHAT, body()));
        assertThat(refusal.relayFailure()).isEqualTo(RelayFailure.CONVERSION_REJECTED);
    }

    @Test
    void aTimeoutIsAnInterruptionBecauseTheRelayGoesOnGeneratingAfterTheGatewayGaveUp() {
        // A relay behind a proxy that gives up after 60 seconds, or Cloudflare after 100: the relay is still at work.
        for (int status : List.of(408, 504, 524)) {
            for (boolean recoverable : List.of(true, false)) {
                UpstreamException timeout = new UpstreamException(status, "Source error " + status + " (server_error)", recoverable, "30");

                Throwable thrown = failureOf(dispatching(Mono.error(timeout)));

                String what = "status " + status;
                assertThat(thrown).as(what).isInstanceOf(RelayAbortedAfterDispatchException.class);
                assertThat(thrown.getCause()).isNull();
                assertThat(((UpstreamException) thrown).getStatusCode()).isEqualTo(502);
                assertThat(((UpstreamException) thrown).isGenerationStarted()).isTrue();
                assertThat(TierRetryPolicy.notStarted(thrown)).as(what).isFalse();
                // Released without a review, and never sent to another account or tier.
                assertThat(facade.tierDefinitive(thrown)).as(what).isTrue();
                Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
                assertThat(classification.interrupted()).as(what).isTrue();
                assertThat(classification.retryable()).as(what).isFalse();
                assertThat(classification.crossAccountRetryAllowed()).as(what).isFalse();
                assertThat(classification.scope()).as(what).isEqualTo(AccountFailureScope.TRANSPORT);
            }
        }
    }

    @Test
    void theRetryAfterOfAnErrorSurvivesTheChangeOfItsStatus() {
        Throwable thrown = failureOf(dispatching(Mono.error(new UpstreamException(501, "Source error 501", true, "30"))));

        assertThat(UpstreamException.retryAfterSeconds(thrown)).isEqualTo(30);
    }

    @Test
    void anInterruptionKeepsItsWordsLosesItsCauseAndIsReleasedAsUnbilled() {
        RelayAbortedAfterDispatchException aborted = new RelayAbortedAfterDispatchException("The exchange broke",
            new IOException("Connection reset by peer " + RELAY_WORDS));

        Throwable thrown = failureOf(dispatching(Mono.error(aborted)));

        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(thrown.getMessage()).isEqualTo("The exchange broke");
        assertThat(thrown.getCause()).isNull();
        assertThat(((UpstreamException) thrown).getStatusCode()).isEqualTo(502);
        assertThat(((UpstreamException) thrown).isRecoverable()).isTrue();
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        assertThat(TierRetryPolicy.notStarted(thrown)).isFalse();
        // One that has no cause to lose is passed on as it is.
        RelayAbortedAfterDispatchException plain = new RelayAbortedAfterDispatchException("The relay stopped");
        assertThat(failureOf(dispatching(Mono.error(plain)))).isSameAs(plain);
    }

    @Test
    void anInterruptionBuriedInAnotherExceptionIsFoundAndAnnounced() {
        RelayAbortedAfterDispatchException aborted = new RelayAbortedAfterDispatchException("The exchange broke");

        Throwable thrown = failureOf(dispatching(Mono.error(new IllegalStateException("outer", aborted))));

        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(thrown.getMessage()).isEqualTo("The exchange broke");
    }

    @Test
    void anErrorThatDoesNotSayWhetherTheRelayGeneratedIsAnInterruptionWithoutTheErrorsWords() {
        List<Throwable> errors = List.of(
            new IOException("connection reset " + RELAY_WORDS),
            new TimeoutException("no answer from " + RELAY_WORDS),
            new IllegalStateException("decoder " + RELAY_WORDS),
            new NullPointerException(),
            // A transport that may have written the request.
            EgressException.maybeSent(EgressFailure.PROXY_TRANSPORT_FAILED, new IOException("reset " + RELAY_WORDS)));
        for (Throwable error : errors) {
            Throwable thrown = failureOf(dispatching(Mono.error(error)));

            assertThat(thrown).as(error.toString()).isInstanceOf(RelayAbortedAfterDispatchException.class);
            assertThat(thrown.getCause()).isNull();
            assertThat(thrown.getMessage()).doesNotContain("relay.internal", "sk-secret", "reset", "decoder");
            assertThat(facade.tierDefinitive(thrown)).isTrue();
            assertThat(TierRetryPolicy.notStarted(thrown)).isFalse();
            // The same errors, as they are, are what the reservation would be left for review for.
            assertThat(facade.tierDefinitive(error)).as(error.toString()).isFalse();
        }
    }

    @Test
    void aFailureThatProvesTheRequestNeverLeftIsClassifiedAsItWasAndIsTriedElsewhere() {
        ConnectException refused = new ConnectException("Connection refused");
        RuntimeException wrapped = new RuntimeException("request failed", refused);
        EgressException noRoute = EgressException.notSent(EgressFailure.PROXY_CONNECT_FAILED, new IOException("no route"));

        for (Throwable error : List.of(refused, wrapped, noRoute)) {
            Throwable thrown = failureOf(dispatching(Mono.error(error)));

            // The egress transport's own failure has a fixed text and the facade does not look past it: it stays.
            if (error == noRoute) {
                assertThat(thrown).isSameAs(error);
            }
            assertThat(facade.tierDefinitive(thrown)).isTrue();
            assertThat(UpstreamFailureClassifier.neverReachedUpstream(thrown)).isTrue();
            Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
            assertThat(classification.retryable()).isTrue();
            assertThat(classification.interrupted()).isFalse();
            assertSameVerdict(classification, UpstreamFailureClassifier.classify(error, account));
        }
    }

    @Test
    void aTransportErrorThatProvesNothingWasSentIsHandedOnWithoutTheHostOrAddressItsWordsNameAndIsClassifiedAsItWas() {
        String host = "relay.internal.example.com";
        List<Throwable> errors = List.of(
            // What netty says of a relay that does not answer a connect in time: its address is in the message, without a cause.
            new io.netty.channel.ConnectTimeoutException("connection timed out after 5000 ms: " + host + "/10.0.0.5:443"),
            new java.net.UnknownHostException(host),
            new java.net.NoRouteToHostException("No route to host: " + host + "/10.0.0.5:443"),
            new ConnectException("Connection refused: " + host + "/10.0.0.5:443"),
            // A certificate that names the host, under the handshake failure, under the envelope of the web client.
            new org.springframework.web.reactive.function.client.WebClientRequestException(
                new javax.net.ssl.SSLHandshakeException("PKIX failed for " + host,
                    new java.security.cert.CertificateException("No subject alternative DNS name matching " + host + " found.")),
                org.springframework.http.HttpMethod.POST, java.net.URI.create("https://" + host + "/v1/chat/completions"),
                new org.springframework.http.HttpHeaders()),
            new org.springframework.web.reactive.function.client.WebClientRequestException(
                new ConnectException("Connection refused: " + host), org.springframework.http.HttpMethod.POST,
                java.net.URI.create("https://" + host + "/v1/chat/completions"), new org.springframework.http.HttpHeaders()));
        for (Throwable error : errors) {
            Throwable thrown = failureOf(dispatching(Mono.error(error)));

            String what = error.getClass().getSimpleName();
            Throwable shown = EgressException.rootCause(thrown);
            // What the client is told and the request log is written from: the innermost cause of the error.
            assertThat(shown.getMessage()).as(what).isEqualTo("The source could not be reached").doesNotContain(host, "10.0.0.5", "443");
            assertThat(thrown.getMessage()).as(what).doesNotContain(host, "10.0.0.5");
            assertThat(thrown.getCause()).as(what).isNull();
            assertThat(thrown.getSuppressed()).as(what).isEmpty();
            // And what the failure is to the rest of the request is what it was.
            assertThat(UpstreamFailureClassifier.neverReachedUpstream(thrown)).as(what).isTrue();
            assertThat(facade.tierDefinitive(thrown)).as(what).isTrue();
            assertThat(TierRetryPolicy.notStarted(thrown)).as(what).isTrue();
            Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
            assertSameVerdict(classification, UpstreamFailureClassifier.classify(error, account));
            assertThat(classification.failure()).as(what).isSameAs(thrown);
            assertThat(classification.failure().getMessage()).as(what).doesNotContain(host, "10.0.0.5");
            assertThat(classification.interrupted()).isFalse();
            // The same for the classification that does not know the dispatch.
            assertSameVerdict(UpstreamFailureClassifier.classify(thrown, account), UpstreamFailureClassifier.classify(error, account));
        }
    }

    /**
     * The relay client proves that a request never left; the list of error classes does not know every transport error
     * that happens before the request, so the dispatcher takes the client's word and classifies what the transport said as
     * it would have if the list had named it.
     */
    @Test
    void aFailureTheRelayClientMarkedAsNotSentIsTriedElsewhereWhateverClassOfErrorItWas() {
        String host = "relay.internal.example.com";
        List<Throwable> errors = List.of(
            new org.springframework.web.reactive.function.client.WebClientRequestException(
                new java.net.SocketException("Connection reset " + host + "/10.0.0.5:443"), org.springframework.http.HttpMethod.POST,
                java.net.URI.create("https://" + host + "/v1/chat/completions"), new org.springframework.http.HttpHeaders()),
            new io.netty.handler.ssl.NotSslRecordException("not an SSL/TLS record: 485454502f312e31 " + host),
            new IOException("Connection reset by peer " + host));
        for (Throwable error : errors) {
            Throwable thrown = failureOf(dispatching(Mono.error(new RelayNotSentException(error))));

            String what = error.getClass().getSimpleName();
            assertThat(thrown).as(what).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
            assertThat(EgressException.rootCause(thrown).getMessage()).as(what).isEqualTo("The source could not be reached");
            assertThat(thrown.getMessage()).as(what).doesNotContain(host, "10.0.0.5");
            assertThat(thrown.getCause()).as(what).isNull();
            assertThat(UpstreamFailureClassifier.neverReachedUpstream(thrown)).as(what).isTrue();
            assertThat(facade.tierDefinitive(thrown)).as(what).isTrue();
            assertThat(TierRetryPolicy.notStarted(thrown)).as(what).isTrue();
            Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
            assertSameVerdict(classification, UpstreamFailureClassifier.classify(error, account));
            assertThat(classification.interrupted()).as(what).isFalse();
            assertThat(classification.failure().getMessage()).as(what).doesNotContain(host, "10.0.0.5");
        }
        // The same errors without the client's word are what they always were: an interruption, which nothing may follow.
        Throwable unmarked = failureOf(dispatching(Mono.error(new java.net.SocketException("Connection reset"))));
        assertThat(unmarked).isInstanceOf(RelayAbortedAfterDispatchException.class);
    }

    private static void assertSameVerdict(Classification actual, Classification expected) {
        assertThat(actual.statusCode()).isEqualTo(expected.statusCode());
        assertThat(actual.type()).isEqualTo(expected.type());
        assertThat(actual.retryable()).isEqualTo(expected.retryable());
        assertThat(actual.crossAccountRetryAllowed()).isEqualTo(expected.crossAccountRetryAllowed());
        assertThat(actual.circuitFailure()).isEqualTo(expected.circuitFailure());
        assertThat(actual.scope()).isEqualTo(expected.scope());
        assertThat(actual.cooldown()).isEqualTo(expected.cooldown());
        assertThat(actual.permanentCredentialFailure()).isEqualTo(expected.permanentCredentialFailure());
        assertThat(actual.relayFailure()).isEqualTo(expected.relayFailure());
    }

    @Test
    void anErrorInTheWorkDoneOnAFinishedAnswerIsAnInterruptionBecauseTheAnswerWasGenerated() {
        // Nothing can be made of this answer, but the relay generated it: it is not sent to anyone else.
        Completion broken = new Completion("text", "", null, StopReason.END_TURN, JSON.createObjectNode());
        when(client.complete(any(), any(), any())).thenReturn(Mono.just(broken));

        Throwable thrown = failureOf(dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(thrown.getCause()).isNull();
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        assertThat(UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body())).interrupted()).isTrue();
    }

    @Test
    void aClientThatFailsWhileItIsBeingCalledIsAnInterruptionToo() {
        when(client.complete(any(), any(), any())).thenThrow(new IllegalStateException("client bug"));

        Throwable thrown = failureOf(dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(facade.tierDefinitive(thrown)).isTrue();
    }

    @Test
    void aDispatchThatIsNotOneForARelayIsRefusedBeforeAnythingIsSentAndMayBeTriedElsewhere() {
        TierDispatch ok = relay(Entry.CHAT, body());
        List<Mono<PlatformCompletionResult>> attempts = List.of(
            dispatcher.dispatch(null, ok, LIMITS, LIMIT),
            dispatcher.dispatch(account, null, LIMITS, LIMIT),
            dispatcher.dispatch(account, TierDispatch.nativeDispatch(), LIMITS, LIMIT),
            dispatcher.dispatch(account, ok, null, LIMIT),
            dispatcher.dispatch(account, ok, LIMITS, null),
            dispatcher.dispatch(account, ok, LIMITS, Duration.ZERO),
            dispatcher.dispatch(account, ok, LIMITS, Duration.ofSeconds(-1)));

        for (Mono<PlatformCompletionResult> attempt : attempts) {
            Throwable thrown = failureOf(attempt);
            assertThat(thrown).isInstanceOf(UpstreamException.class).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
            assertThat(((UpstreamException) thrown).getStatusCode()).isEqualTo(502);
            assertThat(((UpstreamException) thrown).isRecoverable()).isTrue();
            assertThat(facade.tierDefinitive(thrown)).isTrue();
            assertThat(TierRetryPolicy.notStarted(thrown)).isTrue();
        }
        verify(client, never()).complete(any(), any(), any());
    }

    // --- what a failure looks like on the wire ---

    @Test
    void aRelayThatAnswersWithAWebPageHasProducedNothingAndIsTriedElsewhere() throws Exception {
        Relay relay = startRelay(exchange -> respond(exchange, 200, "text/html", "<html><body>Welcome to the relay " + RELAY_WORDS + "</body></html>"));

        Throwable thrown = failureOf(real().dispatch(relayAccount(relay.url()), relay(Entry.MESSAGES, body()), LIMITS, LIMIT));

        assertThat(relay.requests()).isEqualTo(1);
        assertThat(thrown).isInstanceOf(UpstreamException.class).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(((UpstreamException) thrown).getStatusCode()).isEqualTo(502);
        assertThat(((UpstreamException) thrown).isRecoverable()).isTrue();
        assertThat(thrown.getMessage()).doesNotContain("relay.internal", "Welcome");
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        assertThat(TierRetryPolicy.notStarted(thrown)).isTrue();
        Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.MESSAGES, body()));
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.crossAccountRetryAllowed()).isTrue();
        assertThat(classification.interrupted()).isFalse();
    }

    @Test
    void damagedBytesInTheSameReadAsTheEventsBeforeThemAreAnInterruptionAndTheRequestIsNotGeneratedTwice() throws Exception {
        byte[] events = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Here is the plan: \"}}]}\n\n").getBytes(StandardCharsets.UTF_8);
        byte[] damaged = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'};
        byte[] body = new byte[events.length + damaged.length];
        System.arraycopy(events, 0, body, 0, events.length);
        System.arraycopy(damaged, 0, body, events.length, damaged.length);
        Relay relay = startRelay(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });

        Throwable thrown = failureOf(real().dispatch(relayAccount(relay.url()), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(relay.requests()).isEqualTo(1);
        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(TierRetryPolicy.notStarted(thrown)).isFalse();
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
        assertThat(classification.interrupted()).isTrue();
        assertThat(classification.retryable()).isFalse();
        assertThat(classification.crossAccountRetryAllowed()).isFalse();
    }

    @Test
    void anAnswerThatRanOutOfTokensWithNothingToShowIsAnInterruptionAndNotAnEmptyAnswerToTryElsewhere() throws Exception {
        Relay relay = startRelay(exchange -> respond(exchange, 200, "application/json",
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"length\"}]}"));

        Throwable thrown = failureOf(real().dispatch(relayAccount(relay.url()), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(relay.requests()).isEqualTo(1);
        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(TierRetryPolicy.notStarted(thrown)).isFalse();
        Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
        assertThat(classification.interrupted()).isTrue();
        assertThat(classification.retryable()).isFalse();
    }

    @Test
    void aStreamWithAMalformedEventIsAnInterruptionBecauseItWasAlreadyTalking() throws Exception {
        Relay relay = startRelay(exchange -> respond(exchange, 200, "text/event-stream",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: {oops " + RELAY_WORDS + "\n\n"));

        Throwable thrown = failureOf(real().dispatch(relayAccount(relay.url()), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(relay.requests()).isEqualTo(1);
        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(thrown.getMessage()).doesNotContain("oops", "relay.internal");
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        assertThat(UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body())).interrupted()).isTrue();
    }

    @Test
    void aConnectionThatWasNeverMadeIsTheTransportsErrorAndIsTriedElsewhere() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        Throwable thrown = failureOf(real().dispatch(
            relayAccount("http://127.0.0.1:" + closedPort), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(thrown).isNotInstanceOf(UpstreamException.class);
        assertThat(UpstreamFailureClassifier.neverReachedUpstream(thrown)).isTrue();
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.crossAccountRetryAllowed()).isTrue();
        assertThat(classification.scope()).isEqualTo(AccountFailureScope.TRANSPORT);
        assertThat(classification.interrupted()).isFalse();
    }

    @Test
    void aHandshakeThatTheRelaysPortResetsIsARequestThatNeverLeftAndIsTriedElsewhereLikeAnyOtherFailureThatCostNothing() throws Exception {
        for (boolean reset : new boolean[] {true, false}) {
            HandshakeBreaker breaker = new HandshakeBreaker(reset);
            open.add(breaker);

            Throwable thrown = failureOf(real().dispatch(
                relayAccount("https://127.0.0.1:" + breaker.port()), relay(Entry.CHAT, body()), LIMITS, LIMIT));

            String what = reset ? "reset" : "closed";
            assertThat(thrown).as(what).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
            assertThat(UpstreamFailureClassifier.neverReachedUpstream(thrown)).as(what).isTrue();
            assertThat(facade.tierDefinitive(thrown)).as(what).isTrue();
            assertThat(TierRetryPolicy.notStarted(thrown)).as(what).isTrue();
            assertThat(EgressException.rootCause(thrown).getMessage()).as(what).isEqualTo("The source could not be reached");
            Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
            assertThat(classification.retryable()).as(what).isTrue();
            assertThat(classification.crossAccountRetryAllowed()).as(what).isTrue();
            assertThat(classification.scope()).as(what).isEqualTo(AccountFailureScope.TRANSPORT);
            assertThat(classification.interrupted()).as(what).isFalse();
        }
    }

    @Test
    void aTlsHandshakeSpokenToAPortThatAnswersInPlainTextIsARequestThatNeverLeftToo() throws Exception {
        Relay relay = startRelay(exchange -> respond(exchange, 200, "application/json", "{}"));

        Throwable thrown = failureOf(real().dispatch(
            relayAccount(relay.url().replace("http://", "https://")), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(relay.requests()).isZero();
        assertThat(thrown).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(UpstreamFailureClassifier.neverReachedUpstream(thrown)).isTrue();
        Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
        assertThat(classification.retryable()).isTrue();
        assertThat(classification.interrupted()).isFalse();
    }

    @Test
    void aConnectionThatDropsAfterTheRequestIsAnInterruptionThatNamesNeitherTheRelayNorTheTransport() throws Exception {
        RawRelay relay = rawRelay();

        Throwable thrown = failureOf(real().dispatch(relayAccount(relay.url()), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(thrown).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getMessage()).doesNotContain("127.0.0.1", String.valueOf(relay.port()), "sk-test");
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        assertThat(UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body())).interrupted()).isTrue();
    }

    @Test
    void aModelThatIsNotServedIsClassifiedAsSuchAndNeverAsksTwice() throws Exception {
        Relay relay = startRelay(exchange -> respond(exchange, 404, "application/json",
            "{\"error\":{\"message\":\"The model does not exist " + RELAY_WORDS + "\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"));

        Throwable thrown = failureOf(real().dispatch(relayAccount(relay.url()), relay(Entry.CHAT, body()), LIMITS, LIMIT));

        assertThat(relay.requests()).isEqualTo(1);
        assertThat(thrown.getMessage()).doesNotContain("relay.internal", "sk-secret");
        assertThat(facade.tierDefinitive(thrown)).isTrue();
        Classification classification = UpstreamFailureClassifier.classify(thrown, account, relay(Entry.CHAT, body()));
        assertThat(classification.relayFailure()).isEqualTo(RelayFailure.MODEL_NOT_SERVED);
        assertThat(classification.retryable()).isTrue();
    }

    @Test
    void aRelayThatRepeatsItsToolCallIdsIsGivenDistinctOnesInEveryAnswer() throws Exception {
        String answer = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"call_0\",\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}},"
            + "{\"id\":\"call_0\",\"type\":\"function\",\"function\":{\"name\":\"Bash\",\"arguments\":\"{}\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30}}";
        Relay relay = startRelay(exchange -> respond(exchange, 200, "application/json", answer));
        XpDispatcher real = real();
        ProxyAccountEntity relayAccount = relayAccount(relay.url());
        List<String> ids = new ArrayList<>();

        for (int turn = 0; turn < 3; turn++) {
            PlatformCompletionResult result = real.dispatch(relayAccount, relay(Entry.MESSAGES, body()), LIMITS, LIMIT).block(WAIT);
            assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_USE);
            assertThat(result.usageSource()).isEqualTo(UsageSource.REPORTED);
            assertThat(result.usage().inputTokens()).isEqualTo(120);
            result.toolUses().forEach(use -> ids.add(use.id()));
        }

        assertThat(ids).hasSize(6).doesNotHaveDuplicates().allMatch(id -> id.startsWith("toolu_"));
        // What the relay was asked is the body with the wire model name, once per attempt.
        assertThat(relay.requests()).isEqualTo(3);
        assertThat(JSON.readTree(relay.lastBody()).path("model").asString()).isEqualTo(WIRE_MODEL);
    }

    // --- fixtures ---

    private Mono<PlatformCompletionResult> dispatching(Mono<Completion> outcome) {
        when(client.complete(any(), any(), any())).thenReturn(outcome);
        return dispatcher.dispatch(account, relay(Entry.CHAT, body()), LIMITS, LIMIT);
    }

    private int statusOf(int status, boolean recoverable) {
        Throwable thrown = failureOf(dispatching(Mono.error(new UpstreamException(status, "Source error " + status, recoverable))));
        assertThat(facade.tierDefinitive(thrown)).as("status %s", status).isTrue();
        return ((UpstreamException) thrown).getStatusCode();
    }

    private static Throwable failureOf(Mono<?> mono) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        StepVerifier.create(mono).expectErrorSatisfies(error::set).verify(WAIT);
        return error.get();
    }

    private XpDispatcher real() {
        return new XpDispatcher(new OpenAiChatRelayClient(JSON, ProductionWebClients.builder().build()));
    }

    private static ObjectNode body() {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("messages").addObject().put("role", "user").put("content", "Hi");
        body.put("stream", false);
        return body;
    }

    private static TierDispatch relay(Entry entry, ObjectNode body) {
        return relay(entry, body, List.of());
    }

    private static TierDispatch relay(Entry entry, ObjectNode body, List<String> degraded) {
        return relay(entry, body, member(true, true), degraded);
    }

    private static TierDispatch relay(Entry entry, ObjectNode body, Member member, List<String> degraded) {
        return TierDispatch.openAiChat(
            new PairKey(SourceKind.OPENAI_CHAT_RELAY, entry, "anthropic"), "claude-sonnet-4.5", WIRE_MODEL, member, body, degraded);
    }

    private static Member member(boolean cacheReadInPrompt, boolean cacheWriteInPrompt) {
        return new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, false, false, "NONE", 540,
            cacheReadInPrompt, cacheWriteInPrompt);
    }

    private static Completion answer(String text) {
        return new Completion(text, "", List.of(), StopReason.END_TURN, JSON.createObjectNode().put("prompt_tokens", 5).put("completion_tokens", 2));
    }

    private static Completion withCalls(PlatformToolUse... calls) {
        return new Completion("", "", List.of(calls), StopReason.TOOL_USE, JSON.createObjectNode().put("prompt_tokens", 50).put("completion_tokens", 10));
    }

    /** A tool call as the accumulator makes it: the relay's id (when it sent one) is in the raw item only. */
    private static PlatformToolUse call(String relayId, String name) {
        ObjectNode raw = JSON.createObjectNode();
        if (!relayId.isEmpty()) {
            raw.put("id", relayId);
        }
        raw.put("type", "function");
        raw.putObject("function").put("name", name).put("arguments", "{\"path\":\"a\"}");
        ObjectNode input = JSON.createObjectNode().put("path", "a");
        return new PlatformToolUse(relayId, name, input, "function_call", "{\"path\":\"a\"}", raw);
    }

    /** A call of the editing tool as the accumulator makes it, with the arguments exactly as the relay wrote them. */
    private static PlatformToolUse patchCall(String relayId, String arguments) {
        ObjectNode raw = JSON.createObjectNode();
        raw.put("id", relayId);
        raw.put("type", "function");
        raw.putObject("function").put("name", "apply_patch").put("arguments", arguments);
        return new PlatformToolUse(relayId, "apply_patch", JSON.readTree(arguments), "function_call", arguments, raw);
    }

    private static String patchArguments(String patch) {
        return JSON.createObjectNode().put("patch", patch).toString();
    }

    private static ProxyAccountEntity relayAccount(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("relay-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("sk-test");
        return account;
    }

    @FunctionalInterface
    private interface Answer {
        void to(HttpExchange exchange) throws Exception;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private Relay startRelay(Answer answer) throws IOException {
        Relay relay = new Relay(answer);
        open.add(relay);
        return relay;
    }

    /** The JDK server on the loopback address, answering as the test says and counting what it was asked. */
    private static final class Relay implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<byte[]> lastBody = new AtomicReference<>(new byte[0]);

        Relay(Answer answer) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    lastBody.set(exchange.getRequestBody().readAllBytes());
                    requests.incrementAndGet();
                    answer.to(exchange);
                } catch (Exception clientLeft) {
                    // The client ended the exchange.
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requests() {
            return requests.get();
        }

        byte[] lastBody() {
            return lastBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private RawRelay rawRelay() throws IOException {
        RawRelay relay = new RawRelay();
        open.add(relay);
        return relay;
    }

    /** A port that reads the first bytes of whatever connects and then resets (or just closes) the connection. */
    private static final class HandshakeBreaker implements AutoCloseable {

        private final ServerSocket server;
        private final List<Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();

        HandshakeBreaker(boolean reset) throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        try {
                            socket.getInputStream().read(new byte[512]);
                            if (reset) {
                                socket.setSoLinger(true, 0);
                            }
                            socket.close();
                        } catch (IOException ended) {
                            // The client left first.
                        }
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            }, "handshake-breaker");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    /** A server that reads one whole request and closes the connection without a word. */
    private static final class RawRelay implements AutoCloseable {

        private final ServerSocket server;
        private final List<Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();

        RawRelay() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        Thread worker = new Thread(() -> serve(socket), "raw-relay-worker");
                        worker.setDaemon(true);
                        worker.start();
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            }, "raw-relay-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void serve(Socket socket) {
            try {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int matched = 0;
                while (matched < 4) {
                    int next = in.read();
                    if (next < 0) {
                        return;
                    }
                    head.write(next);
                    matched = next == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : next == '\r' ? 1 : 0;
                }
                String text = head.toString(StandardCharsets.ISO_8859_1).toLowerCase();
                int at = text.indexOf("content-length:");
                int length = at < 0 ? 0 : Integer.parseInt(text.substring(at + "content-length:".length(), text.indexOf("\r\n", at)).strip());
                in.readNBytes(length);
                socket.close();
            } catch (Exception ended) {
                // The client left.
            }
        }

        int port() {
            return server.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
