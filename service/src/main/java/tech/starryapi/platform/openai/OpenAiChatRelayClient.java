package tech.starryapi.platform.openai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.egress.AccountEgressTransport;
import tech.starryapi.egress.EgressException;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.platform.xproto.RelayNotSentException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks an OpenAI-format relay one chat completions question and reads the answer (design 7.7): the HTTP half of a
 * cross-protocol source. It is not {@link OpenAiUpstreamClient}, which is left as it is: that client lowers and
 * prefixes the model name, asks again with another name when the relay says the model is unknown, forces the request to
 * be non-streaming and reads only a JSON body. A relay that serves Claude under an exact name and charges for every
 * generation needs none of that.
 *
 * <ul>
 *   <li><b>One POST per subscription</b>, to the chat completions endpoint of the account's base URL
 *       ({@link OpenAiEndpoints}), with the body exactly as given: the wire model name, its case and the {@code
 *       stream} flag are the caller's. The body is serialized once, so the relay gets the bytes the caller built.
 *       The only headers are the account's bearer key, the content type and an accept header: nothing of the
 *       client's request reaches the relay (design 7.12). Nothing in here asks again, whatever the answer is.
 *   <li><b>The answer is read as it arrives</b>: the body is fed piece by piece to a {@link
 *       ChatCompletionAccumulator}, which decides from the first byte whether it is a JSON body or an event stream, so
 *       a relay that streams when it was asked for JSON (or the other way round) is read all the same. An answer that
 *       is known to be a failure ends the exchange at once and closes the connection. At most 16 MB are read.
 *   <li><b>Time</b>: {@code timeLimit} bounds the whole exchange, connection to last byte (design 4.6). The read
 *       timeout of the shared client only bounds the gap between two reads, which a stream that keeps coming is never
 *       held to.
 * </ul>
 *
 * <p>What a failure is, for the caller that must never generate twice (invariant I7). The error is delivered as an
 * {@link UpstreamException} wherever the gateway can say what happened, and as the transport's own error (inside a {@link
 * RelayNotSentException}) where it can prove that nothing was sent:
 *
 * <ul>
 *   <li>The relay answered with an error status: a {@link tech.starryapi.platform.xproto.RelayErrorException} (design
 *       7.11), whose message holds none of the relay's words. The body is read for the mapper up to 64 KB and for at most
 *       ten seconds. A server error that arrives once the relay may have been generating (thirty seconds after the
 *       request was written, the rule of the accumulator) is a {@link RelayAbortedAfterDispatchException} instead: a
 *       relay or a gateway in front of it that gave up late gives no proof that it produced nothing, and a 5xx that
 *       comes in a moment is.
 *   <li>The answer is a failure the accumulator knows (an error inside a 2xx, an empty or unfinished answer, data that
 *       is not a completion, a size over the limit): the exception the accumulator throws, which is a recoverable 502
 *       while nothing can have been generated and a {@link RelayAbortedAfterDispatchException} after.
 *   <li>The exchange broke after the request was handed to the connection, or the time limit ran out: a {@link
 *       RelayAbortedAfterDispatchException} (design 7.7, the transport rows), with the transport error as its cause.
 *       The request counts as handed over from the moment the connection is ready and the request is about to be
 *       written, the same moment the egress transport uses; an {@link EgressException} says for itself whether the
 *       request may have been sent.
 *   <li>The request provably never left, because the connection was not ready when the transport failed (no
 *       connection, no route, a refused connect, a handshake that was refused, reset or closed): a {@link
 *       RelayNotSentException} with the transport's error as its cause, so that the existing classification of a failure
 *       that cost nothing applies to the cause, whatever class of error it is. An egress failure says for itself that
 *       it was not sent and is returned as it is. The words of the cause can name the relay's host and address, so the
 *       caller that hands it on ({@code XpDispatcher}) takes them out.
 * </ul>
 *
 * <p>The caller still wraps whatever else may come out, an unexpected error in the code above included, by
 * what it can know about the request.
 */
@Component
public class OpenAiChatRelayClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatRelayClient.class);

    /** How much of an error body the error mapper is given; it reads no more than this either. */
    private static final int ERROR_BODY_LIMIT_BYTES = 64 * 1024;

    /** An error status is answered by its status even when the body that explains it does not arrive. */
    private static final Duration ERROR_BODY_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final RelayErrorMapper errorMapper;
    private final LongSupplier nanoTime;

    @Autowired
    public OpenAiChatRelayClient(ObjectMapper objectMapper, WebClient upstreamWebClient) {
        this(objectMapper, upstreamWebClient, System::nanoTime);
    }

    /** @param nanoTime the clock the thirty seconds of design 7.7 are counted by */
    OpenAiChatRelayClient(ObjectMapper objectMapper, WebClient upstreamWebClient, LongSupplier nanoTime) {
        this.objectMapper = objectMapper;
        this.webClient = upstreamWebClient;
        this.errorMapper = new RelayErrorMapper(objectMapper);
        this.nanoTime = nanoTime;
    }

    /**
     * Sends {@code wireBody} to the relay of {@code account} and returns what the relay answered. Every subscription
     * is one request.
     *
     * @param wireBody  the chat completions request as the relay is to get it, model name included
     * @param timeLimit how long the whole exchange may take; positive
     */
    public Mono<Completion> complete(ProxyAccountEntity account, JsonNode wireBody, Duration timeLimit) {
        if (account == null) {
            throw new IllegalArgumentException("The source account is required");
        }
        if (wireBody == null || !wireBody.isObject()) {
            throw new IllegalArgumentException("The request body must be a JSON object");
        }
        if (timeLimit == null || timeLimit.isZero() || timeLimit.isNegative()) {
            throw new IllegalArgumentException("The time limit must be positive");
        }
        return Mono.defer(() -> new Exchange(account).run(wireBody, timeLimit));
    }

    /** One request and its answer: the state that decides what a failure of it means. */
    private final class Exchange {

        private final ProxyAccountEntity account;
        private final String source;
        private final ChatCompletionAccumulator accumulator;
        private final AtomicBoolean handedOver = new AtomicBoolean();

        Exchange(ProxyAccountEntity account) {
            this.account = account;
            this.source = account.getAccountId();
            this.accumulator = new ChatCompletionAccumulator(
                objectMapper, error -> errorMapper.fromInBand(error, source, account.getAccessToken()), nanoTime);
        }

        Mono<Completion> run(JsonNode wireBody, Duration timeLimit) {
            byte[] body = objectMapper.writeValueAsBytes(wireBody);
            boolean streaming = wireBody.path("stream").asBoolean(false);
            String endpoint = OpenAiEndpoints.openAiApi(account.getBaseUrl(), "/chat/completions");
            return webClient.post()
                .uri(endpoint)
                .attribute(AccountEgressTransport.ACCOUNT, account)
                .headers(headers -> applyHeaders(headers, streaming))
                .httpRequest(request -> requestIsBeingWritten())
                .bodyValue(body)
                .exchangeToMono(this::receive)
                .timeout(timeLimit, Mono.defer(() -> Mono.error(timeLimitReached())))
                .onErrorMap(this::interpret);
        }

        private void applyHeaders(HttpHeaders headers, boolean streaming) {
            headers.setBearerAuth(account.getAccessToken());
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(streaming
                ? List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                : List.of(MediaType.APPLICATION_JSON));
        }

        /** Runs when the connection is ready and the request is about to be written, which is when it may be sent. */
        private void requestIsBeingWritten() {
            handedOver.set(true);
            accumulator.requestWritten();
        }

        private Mono<Completion> receive(ClientResponse response) {
            HttpStatusCode status = response.statusCode();
            if (!status.is2xxSuccessful()) {
                String retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                // Asked when the status arrives, before the body is waited for: the body adds up to ten seconds of its own.
                boolean late = accumulator.possiblyGenerated();
                return errorBody(response).flatMap(text -> {
                    RelayErrorException mapped = errorMapper.fromResponse(
                        status.value(), text, retryAfter, source, account.getAccessToken());
                    // Only a server error that the verdict leaves a server error: a late 503 that says the model is
                    // not served, or the prompt too long, is a refusal whenever it comes.
                    return Mono.<Completion>error(late && status.is5xxServerError() && mapped.getStatusCode() >= 500
                        ? aborted("The source failed after it may have started to answer", null)
                        : mapped);
                });
            }
            return pieces(response)
                .doOnNext(accumulator::acceptBytes)
                // [DONE] ends the answer: what a relay does after it (a close without the last chunk, a connection it
                // keeps open, bytes it writes: the accumulator ignores those, in the read of [DONE] as in a later one)
                // cannot make a complete, paid answer an interruption. Cancelling closes the connection.
                .takeUntil(piece -> accumulator.isDone())
                .then(Mono.fromSupplier(accumulator::finish));
        }

        /**
         * What the relay wrote with its error status, as far as it came: the mapper needs the words only to pick
         * between two statuses, and the status decides without them.
         */
        private Mono<String> errorBody(ClientResponse response) {
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            return pieces(response)
                .doOnNext(piece -> read.write(piece, 0, Math.max(0, Math.min(piece.length, ERROR_BODY_LIMIT_BYTES - read.size()))))
                .takeUntil(piece -> read.size() >= ERROR_BODY_LIMIT_BYTES)
                .then(Mono.fromSupplier(() -> read.toString(StandardCharsets.UTF_8)))
                .timeout(ERROR_BODY_TIMEOUT)
                .onErrorResume(error -> Mono.fromSupplier(() -> read.toString(StandardCharsets.UTF_8)));
        }

        /** The body in the pieces it arrives in; a piece is copied and its buffer released at once. */
        private Flux<byte[]> pieces(ClientResponse response) {
            return response.bodyToFlux(DataBuffer.class).map(buffer -> {
                try {
                    byte[] piece = new byte[buffer.readableByteCount()];
                    buffer.read(piece);
                    return piece;
                } finally {
                    DataBufferUtils.release(buffer);
                }
            });
        }

        private UpstreamException timeLimitReached() {
            if (!handedOver.get()) {
                // Not even a connection by then: nothing was sent, so another source may answer.
                return new UpstreamException(502, "The source could not be reached within the time limit", true);
            }
            return aborted("The source did not finish its answer within the time limit", null);
        }

        /** What a failure of the exchange is: see the class description. */
        private Throwable interpret(Throwable error) {
            if (error instanceof UpstreamException) {
                return error;
            }
            EgressException egress = EgressException.find(error);
            boolean mayHaveBeenSent = egress != null ? egress.requestMayHaveBeenSent() : handedOver.get();
            if (!mayHaveBeenSent) {
                // An egress failure that was not sent says so by its type; any other error is marked as one that was not.
                return egress != null ? error : new RelayNotSentException(error);
            }
            return aborted("The exchange with the source broke after the request was sent", error);
        }

        private RelayAbortedAfterDispatchException aborted(String message, Throwable cause) {
            // Class names only: a transport message can hold an address or a proxy credential.
            log.warn("Relay exchange interrupted: source={} reason={} cause={}", source, message, describe(cause));
            return new RelayAbortedAfterDispatchException(message, cause);
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "none";
        }
        Throwable root = error;
        for (int i = 0; i < 20 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return error.getClass().getSimpleName() + (root == error ? "" : "/" + root.getClass().getSimpleName());
    }
}
