package tech.starryapi.proxy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.egress.EgressException;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.openai.ApplyPatchEmulation;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.RelayNotSentException;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Caliber;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Limits;
import tech.starryapi.proxy.tier.TierDispatch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends the request of a tiered attempt to the OpenAI-format relay a tier selected and hands back what the platform
 * calls an answer (design 7.5): the one place that turns the exchange with a relay into a {@link
 * PlatformCompletionResult} or into a failure the rest of the request can reason about. The facade calls it where a
 * native attempt calls an upstream client, for a {@link TierDispatch} of kind {@code OPENAI_CHAT}; an untiered request
 * never gets here.
 *
 * <ul>
 *   <li><b>The request</b> is the dispatch's body with the wire model name written into a copy. The body is shared
 *       between the members of a plan whose settings agree and is not edited.
 *   <li><b>The answer</b> gets the usage {@link RelayUsageNormalizer} makes of what the relay reported, bounded by the
 *       limits the tier reserved, and its tool calls get ids the gateway mints: the relay's own ids may be empty, may
 *       repeat from one answer to the next ({@code call_0} every time), and are the relay's, while the client keeps
 *       them for the next turn. Messages entries get {@code toolu_} ids, the others {@code call_} ones, 24 base62
 *       characters of a secure random each, distinct within the answer. The relay's id is not kept: a client-facing
 *       renderer copies the raw item of a tool call into what it writes, and the relay's id (and the Chat shape of its
 *       item) would reach the client through it, so a tool call leaves here without one. On the Responses entry the
 *       calls of the editing tool, {@code apply_patch}, are turned into the custom tool calls Codex declared it as, with
 *       the patch for their input, as the native path does for the accounts that speak Responses ({@link
 *       ApplyPatchEmulation}; the relay was offered a function of that name with one {@code patch} argument).
 *   <li><b>An answer the Responses entry cannot be told</b> is an interruption too (see {@link #cannotBeTold}): the clients
 *       of that entry that matter take {@code response.incomplete} for a failure of the stream and send the request again,
 *       and a relay that is asked again answers again and is paid for it. So an answer that the relay's filter stopped, and
 *       one that the gateway's own bound cut off (the client named no limit, so the relay was held to the default the
 *       reservation assumes) when it lost a call or has nothing to show, are not told as {@code incomplete}: nothing is
 *       charged for them, the relay is kept from the session for a couple of minutes, and the client's retry reaches
 *       another source, which is not held to that bound.
 *   <li><b>Every failure is the gateway's own</b>, and never one that leaves a reservation for review: a cross-protocol
 *       attempt is generated once, and whatever goes wrong with it is absorbed by the operator (invariant I7). The
 *       relay client already says what it can prove, and an {@link UpstreamException} it raises is kept (its status is
 *       made one that is not billed, see {@link #definitive}). What it can not name, an error of any other kind once
 *       the request was handed to the connection, and any error in the work done here on a finished answer, is a
 *       {@link RelayAbortedAfterDispatchException}: the relay may have generated, so nothing is sent to anyone else.
 *       Only a failure that proves the request never left (no route, a refused connect, a handshake that was reset:
 *       the relay client has marked it, or the transport's error is of a class that says so) stays as it is, so that the
 *       classification of a failure that cost nothing applies and the request goes on to another source. None of the
 *       gateway's exceptions has a cause: what the transport said can name the relay or an address, and the
 *       facade shows the innermost cause of an error to the client and the user's request log. So even a failure that
 *       is kept for its classification is handed on in a fixed text without its cause, unless it is the egress
 *       transport's own, whose text is fixed and whose cause the facade does not look past.
 * </ul>
 */
@Component
class XpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(XpDispatcher.class);

    /** Characters of the minted part of a tool call id. */
    static final int TOOL_ID_LENGTH = 24;

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String MESSAGES_TOOL_PREFIX = "toolu_";
    private static final String CALL_TOOL_PREFIX = "call_";

    /** Its response side only reads the arguments of a call, so any mapper will do; the request side is the converter's. */
    private static final ApplyPatchEmulation APPLY_PATCH = new ApplyPatchEmulation(new ObjectMapper());

    /** Statuses of a relay's error that the reservation's release treats as not billed, whatever else they mean. */
    private static final Set<Integer> UNBILLED_CLIENT_STATUSES = Set.of(400, 401, 402, 403, 404, 422, 429);
    private static final Set<Integer> UNBILLED_SERVER_STATUSES = Set.of(500, 502, 503, 529);

    private final OpenAiChatRelayClient client;
    private final RandomGenerator random;

    @Autowired
    XpDispatcher(OpenAiChatRelayClient client) {
        this(client, new SecureRandom());
    }

    XpDispatcher(OpenAiChatRelayClient client, RandomGenerator random) {
        this.client = Objects.requireNonNull(client, "client");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * One attempt: exactly one request to the relay of {@code account} per subscription, nothing before it.
     *
     * @param dispatch the dispatch the tier selected for the account; its kind has to be {@code OPENAI_CHAT}
     * @param limits what the tier reserved: the usage is bounded to them
     * @param timeLimit how long the whole exchange may take (design 4.6)
     */
    Mono<PlatformCompletionResult> dispatch(
        ProxyAccountEntity account,
        TierDispatch dispatch,
        Limits limits,
        Duration timeLimit
    ) {
        return dispatch(account, dispatch, limits, timeLimit, false);
    }

    /**
     * {@link #dispatch(ProxyAccountEntity, TierDispatch, Limits, Duration)} for a request whose output limit may be the
     * gateway's own.
     *
     * @param defaultOutputLimit whether the limit the relay was told it may write is the default the reservation assumes
     *     and not one the client wrote: the Responses entry's answers that this bound cut off are judged by it
     */
    Mono<PlatformCompletionResult> dispatch(
        ProxyAccountEntity account,
        TierDispatch dispatch,
        Limits limits,
        Duration timeLimit,
        boolean defaultOutputLimit
    ) {
        return Mono.defer(() -> {
            ObjectNode body;
            try {
                body = requestBody(account, dispatch, limits, timeLimit);
            } catch (RuntimeException invalid) {
                // A mistake of the caller, found before anything was sent: another source may still answer.
                log.error("A cross-protocol dispatch could not be prepared", invalid);
                return Mono.error(new UpstreamException(502, "The cross-protocol dispatch could not be prepared", true));
            }
            return Mono.defer(() -> client.complete(account, body, timeLimit))
                .map(completion -> answer(account, dispatch, body, completion, limits, defaultOutputLimit))
                .onErrorMap(error -> settled(account, error));
        });
    }

    private static ObjectNode requestBody(ProxyAccountEntity account, TierDispatch dispatch, Limits limits, Duration timeLimit) {
        if (account == null) {
            throw new IllegalArgumentException("The source account is required");
        }
        if (dispatch == null || dispatch.kind() != TierDispatch.Kind.OPENAI_CHAT) {
            throw new IllegalArgumentException("Only a dispatch to an OpenAI-format relay is made here");
        }
        if (limits == null) {
            throw new IllegalArgumentException("The limits of the reservation are required");
        }
        if (timeLimit == null || timeLimit.isZero() || timeLimit.isNegative()) {
            throw new IllegalArgumentException("The time limit must be positive");
        }
        // The model first, as clients write it; the rest is the shared body's, whose subtrees are not copied.
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("model", dispatch.wireModel());
        dispatch.wireBody().properties().forEach(field -> {
            if (!"model".equals(field.getKey())) {
                body.set(field.getKey(), field.getValue());
            }
        });
        return body;
    }

    private PlatformCompletionResult answer(
        ProxyAccountEntity account,
        TierDispatch dispatch,
        JsonNode sent,
        Completion completion,
        Limits limits,
        boolean defaultOutputLimit
    ) {
        // "Contained" is the safe reading of a switch that was left open: a prompt wrongly taken to contain the cache can
        // only be billed less (design 5.8).
        Caliber caliber = new Caliber(
            !Boolean.FALSE.equals(dispatch.member().usageCacheReadInPrompt()),
            !Boolean.FALSE.equals(dispatch.member().usageCacheWriteInPrompt())
        );
        if (dispatch.pair().entry() == Entry.MESSAGES && completion.stopReason() == StopReason.END_TURN
            && (completion.content() == null || completion.content().isBlank())
            && (completion.toolUses() == null || completion.toolUses().isEmpty())) {
            // Reasoning alone. A Messages answer has no place for it (it is never given as thinking, which would need a
            // signature), so it would be an empty text block and end_turn: the loop of Claude Code stops without a word
            // and the user pays for tokens that said nothing. The relay generated it: an interruption, as an empty answer.
            throw new RelayAbortedAfterDispatchException("The source returned an answer with no text");
        }
        if (dispatch.pair().entry() == Entry.RESPONSES) {
            String reason = cannotBeTold(completion, defaultOutputLimit);
            if (reason != null) {
                throw new RelayAbortedAfterDispatchException(reason);
            }
        }
        RelayUsageNormalizer.Result usage = RelayUsageNormalizer.normalize(completion, sent, caliber, limits);
        if (usage.suspectedUnderReport()) {
            log.info("A relay reported much less input than the request weighs: source={} model={}",
                account.getAccountId(), dispatch.modelId());
        }
        return new PlatformCompletionResult(
            completion.content(),
            completion.reasoningContent(),
            withMintedIds(completion.toolUses(), dispatch.pair().entry()),
            usage.usage(),
            completion.stopReason(),
            usage.source(),
            dispatch.degraded()
        );
    }

    /**
     * Why an answer of the Responses entry is not one to tell the client, or null when it is. {@code incomplete} is the
     * only thing that entry can say of an answer that did not finish, and Codex takes it for an error of the stream: it
     * sends the same request again, five more times, and every answer of a relay is paid for by the user who asked, who
     * is told nothing of it. So the answers that would be told so are not told:
     *
     * <ul>
     *   <li>One that the relay's filter stopped. Another source may answer the request, and the same relay is likely to
     *       stop the retry in the same place.
     *   <li>One that the gateway's own bound cut off ({@code defaultOutputLimit}: the client wrote none, so the relay was
     *       held to the default the reservation assumes, which the native accounts, that strip it, are not) and that lost a
     *       call to it, or that has nothing the client can show: no text and no whole call, only reasoning. An answer
     *       that is text alone, or has the calls it came to, is a finished turn to every native account's client and is
     *       told as {@code completed} by the facade ({@code ProxyFacade#responsesAnswerOfRelay}); one that lost a call
     *       would end the turn without the step the model was taking, or run the calls it kept without the one it cut.
     * </ul>
     *
     * A limit the client named is its own, and an answer that reached it is {@code incomplete} to the client that asked
     * for it.
     */
    private static String cannotBeTold(Completion completion, boolean defaultOutputLimit) {
        if (completion.stopReason() == StopReason.CONTENT_FILTER) {
            return "The source stopped its answer with its content filter";
        }
        if (completion.stopReason() == StopReason.MAX_TOKENS && defaultOutputLimit) {
            boolean text = completion.content() != null && !completion.content().isBlank();
            boolean calls = completion.toolUses() != null && !completion.toolUses().isEmpty();
            if (completion.callCut() || (!text && !calls)) {
                return "The source's answer was cut off before it could be used";
            }
        }
        return null;
    }

    /**
     * The tool calls of the answer under ids of the gateway's. The relay's own id is not passed on, nor the raw item it
     * came in (the Chat shape of a call, which a Responses renderer would copy into its own item); the name, the
     * arguments as the relay wrote them and the type are, except that a call of the editing tool on the Responses entry
     * is the custom tool call Codex expects ({@link #editingToolCall}).
     */
    private List<PlatformToolUse> withMintedIds(List<PlatformToolUse> calls, Entry entry) {
        String prefix = entry == Entry.MESSAGES ? MESSAGES_TOOL_PREFIX : CALL_TOOL_PREFIX;
        Set<String> minted = new HashSet<>();
        List<PlatformToolUse> result = new ArrayList<>(calls.size());
        for (PlatformToolUse call : calls) {
            String id;
            do {
                id = prefix + randomId();
            } while (!minted.add(id));
            result.add(entry == Entry.RESPONSES && ApplyPatchEmulation.TOOL_NAME.equals(call.name())
                ? editingToolCall(id, call)
                : new PlatformToolUse(id, call.name(), call.input(), call.type(), call.rawInput(), null));
        }
        return result;
    }

    /**
     * A call of the editing tool as a Responses client is given it: a function to the relay and a custom tool to Codex, so
     * it is a custom tool call whose input is the patch itself and not the JSON of the arguments.
     */
    private static PlatformToolUse editingToolCall(String id, PlatformToolUse call) {
        String type = APPLY_PATCH.toolUseType(call.type(), call.name());
        String arguments = call.rawInput() != null ? call.rawInput() : String.valueOf(call.input());
        return new PlatformToolUse(id, call.name(), call.input(), type, APPLY_PATCH.toolUseRawInput(type, call.name(), arguments), null);
    }

    private String randomId() {
        char[] id = new char[TOOL_ID_LENGTH];
        for (int i = 0; i < id.length; i++) {
            id[i] = BASE62.charAt(random.nextInt(BASE62.length()));
        }
        return new String(id);
    }

    // --- failures ---

    /** What a failure of the exchange is to the rest of the request: see the class description. */
    private Throwable settled(ProxyAccountEntity account, Throwable error) {
        RelayAbortedAfterDispatchException aborted = find(error, RelayAbortedAfterDispatchException.class);
        if (aborted != null) {
            return aborted.getCause() == null && aborted == error ? aborted : new RelayAbortedAfterDispatchException(aborted.getMessage());
        }
        UpstreamException upstream = find(error, UpstreamException.class);
        if (upstream != null) {
            return definitive(upstream);
        }
        // The relay client knows whether the request was ever handed to the connection; a list of error classes can only
        // guess it from the error, and does not name a handshake that a firewall reset or a plain port that was spoken
        // TLS to. What the client has marked is as good as what the list knows, and the list still covers an error that
        // did not come through the client.
        RelayNotSentException notSent = find(error, RelayNotSentException.class);
        if (notSent != null || UpstreamFailureClassifier.neverReachedUpstream(error)) {
            if (EgressException.find(error) != null) {
                return error;
            }
            // A transport error that proves nothing was sent: the connect timeout of netty names the host and the address
            // in its message, and a certificate error names the host. It is classified as it is, in a fixed text.
            Throwable unsent = notSent != null && notSent.getCause() != null ? notSent.getCause() : error;
            log.info("A cross-protocol dispatch did not reach the relay: source={} cause={}",
                account.getAccountId(), describe(unsent));
            return UpstreamFailureClassifier.unreached(unsent, account);
        }
        // Class names only: a transport message can hold an address or a proxy credential.
        log.warn("A cross-protocol dispatch failed in a way that does not say whether the relay generated: source={} cause={}",
            account.getAccountId(), error.getClass().getSimpleName());
        return new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent");
    }

    /**
     * The failure of a relay as one that is released without a review. The reservation of a failed attempt is released
     * as unbilled only for some statuses (400, 401, 403, 404, 422, 429, and a recoverable 500, 502, 503 or 529; a 402 is
     * counted by the tier rule), and a relay says others: 409 for a conflict, a 501 or another server error, a 413 for a
     * body it will not read. Every one of them is the relay's refusal to answer, so it becomes the status of the same
     * kind that is: a conflict a recoverable 502, any other server error a recoverable 503, any other refusal a 400. The
     * failure classification takes the first two as it took them, and the client, which sees everything unknown as a
     * 400, is told no less. The message keeps the relay's status in its words ({@code Source error 501}).
     *
     * <p>A timeout is not a refusal: 408, and the 504 and 524 of the gateway in front of the relay, say that the time ran
     * out while the relay was working, which it goes on doing (and being paid for) after the gateway has given up. They
     * are an interruption, so that nothing else is asked (invariant I7); the client already does the same with any
     * server error that arrives after the first thirty seconds.
     *
     * <p>A server error that is not recoverable (the relay client makes none: it is what an upstream client says for a
     * body it could not read) and a status below 400 do not say whether the relay generated, and are an interruption.
     */
    private static UpstreamException definitive(UpstreamException error) {
        int status = error.getStatusCode();
        if (RelayErrorMapper.isTimeoutStatus(status)) {
            return new RelayAbortedAfterDispatchException("The source did not answer within its time limit");
        }
        if (UNBILLED_CLIENT_STATUSES.contains(status) || (error.isRecoverable() && UNBILLED_SERVER_STATUSES.contains(status))) {
            return error;
        }
        int seconds = UpstreamException.retryAfterSeconds(error);
        String retryAfter = seconds > 0 ? Integer.toString(seconds) : null;
        if (status == 409) {
            return new UpstreamException(502, error.getMessage(), true, retryAfter);
        }
        if (status >= 500 && error.isRecoverable()) {
            return new UpstreamException(503, error.getMessage(), true, retryAfter);
        }
        if (status >= 400 && status < 500) {
            return new UpstreamException(400, error.getMessage(), false, retryAfter);
        }
        return new RelayAbortedAfterDispatchException("The source failed in a way that does not say whether it generated");
    }

    /** The class of the error and of the innermost cause, for the log: a transport message can hold an address. */
    private static String describe(Throwable error) {
        Throwable root = error;
        for (int i = 0; i < 20 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return error.getClass().getSimpleName() + (root == error ? "" : "/" + root.getClass().getSimpleName());
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }
}
