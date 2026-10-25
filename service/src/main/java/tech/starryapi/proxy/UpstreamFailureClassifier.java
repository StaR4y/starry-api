package tech.starryapi.proxy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.egress.EgressException;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorException;
import tech.starryapi.proxy.tier.TierDispatch;

final class UpstreamFailureClassifier {

    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(45);

    /** The statuses besides 404 with which a relay says it does not serve the model, when its words say so. */
    private static final Set<Integer> RELAY_MODEL_STATUSES = Set.of(400, 403, 422, 503);

    /**
     * What a relay says when it does not serve a model: the union of what the OpenAI client retries on and what
     * {@link #forbiddenFailure} reads as a model the account may not use. Matched after '_' and '-' are taken for spaces,
     * so the one token the relay error mapper leaves ({@code model_not_found}) is found.
     */
    private static final List<String> MODEL_NOT_SERVED = List.of(
        "model not found", "no available channel", "model does not exist", "does not support model",
        "does not exist or you do not have access", "do not have access to the model",
        "does not have access to model", "not allowed to use this model", "not allowed to use model",
        "access to this model", "unsupported model", "model is not supported"
    );

    private UpstreamFailureClassifier() {
    }

    static Classification classify(Throwable error) {
        return classify(error, null);
    }

    /**
     * What the answer of an OpenAI-format relay to a cross-protocol attempt (design 7.8) means, which the rules for the
     * upstreams of the platform do not say: a relay is a stranger that may be generating when it stops answering, that
     * serves the models its administrator mapped and not the ones the account's platform serves, and that is asked
     * with a request converted from another protocol, which it may refuse for reasons no native upstream has. The
     * dispatch of a native attempt, and no dispatch at all, are classified by the other overload, unchanged.
     *
     * <ul>
     *   <li>An interruption ({@link RelayAbortedAfterDispatchException}: the exchange broke or ran out of time after the
     *       request was written, or the relay stopped after its first output) is a transport failure with its
     *       one-minute cooldown that is not retried anywhere, whatever else its cause says: the relay may be generating
     *       the answer, and a second attempt would pay for it twice.
     *   <li>A model the relay does not serve (a 404, or a 400, 403, 422 or 503 that says so) is the model's problem on
     *       that account: ten minutes of cooldown, keyed by the mapping's model id ({@link TierDispatch#modelScope}),
     *       and the request goes on to another account or tier.
     *   <li>A prompt that does not fit the relay's context window, and any other 400, 413 or 422 (the converted request
     *       was refused), are the request's problem with this source, not the account's: no cooldown, no retry on
     *       another source of the same kind, but one crossing to a tier whose first source is a native one is allowed
     *       ({@link Classification#crossTierOnly()}). Such a refusal says the relay did not generate.
     *   <li>Everything else (402, 401, 403, 408, 409, 429, a 5xx) is judged as it is for any other upstream, because the
     *       relay's status is all there is to go by. A 402 in particular is the relay's account out of money: the same
     *       capacity failure it is today, which a tiered request counts as unbilled.
     * </ul>
     *
     * <p>A failure that proves the request never left (the route could not be built, the connection was refused) is
     * the transport's to classify as well: it cost nothing and says nothing about what the relay does.
     */
    static Classification classify(Throwable error, ProxyAccountEntity account, TierDispatch dispatch) {
        if (dispatch == null || dispatch.kind() != TierDispatch.Kind.OPENAI_CHAT) {
            return classify(error, account);
        }
        RelayAbortedAfterDispatchException aborted = findCause(error, RelayAbortedAfterDispatchException.class);
        if (aborted != null) {
            // The exception's own words, not the transport error under it: that one can name the relay.
            return relayClassification(
                aborted, aborted.getStatusCode(), AccountFailureScope.TRANSPORT, false, false, Duration.ofMinutes(1),
                RelayFailure.INTERRUPTED
            );
        }
        UpstreamException upstream = EgressException.find(error) == null ? findCause(error, UpstreamException.class) : null;
        if (upstream == null) {
            return classify(error, account);
        }
        int status = upstream.getStatusCode();
        if (RelayErrorException.contextExceeded(error)) {
            return relayClassification(
                upstream, status, AccountFailureScope.REQUEST, false, false, Duration.ZERO, RelayFailure.CONTEXT_EXCEEDED
            );
        }
        if (status == 404 || (RELAY_MODEL_STATUSES.contains(status) && isModelNotServed(upstream))) {
            return relayClassification(
                upstream, status, AccountFailureScope.CAPACITY_MODEL, true, true, Duration.ofMinutes(10), RelayFailure.MODEL_NOT_SERVED
            );
        }
        if (status == 400 || status == 413 || status == 422) {
            return relayClassification(
                upstream, status, AccountFailureScope.REQUEST, false, false, Duration.ZERO, RelayFailure.CONVERSION_REJECTED
            );
        }
        return classify(error, account);
    }

    static Classification classify(Throwable error, ProxyAccountEntity account) {
        RelayUnreachedException unreached = findCause(error, RelayUnreachedException.class);
        if (unreached != null) {
            return unreached.classification();
        }
        EgressException egress = EgressException.find(error);
        if (egress != null) {
            // A broken outbound route says nothing about the account itself, so it never counts as an account
            // failure (REQUEST scope, no circuit failure, no cooldown). The same account is never retried and the
            // request is never sent directly; a request that provably never left may use another account, while
            // one that may have been written stays on its account like a response timeout after dispatch.
            boolean notSent = !egress.requestMayHaveBeenSent();
            return new Classification(egress, null,
                notSent ? AccountPoolService.UpstreamErrorType.RECOVERABLE : AccountPoolService.UpstreamErrorType.FATAL,
                notSent, notSent, false, AccountFailureScope.REQUEST, Duration.ZERO, false);
        }
        UpstreamException upstream = findCause(error, UpstreamException.class);
        Throwable failure = rootFailure(error);
        if (upstream == null) {
            if (isResponseTimeoutAfterDispatch(error)) {
                // The request was sent and the upstream simply did not answer within our timeout. That says
                // nothing about the account's health, and sending the same request to another account would
                // start (and bill) a second generation while the first may still be running.
                return new Classification(
                    failure,
                    null,
                    AccountPoolService.UpstreamErrorType.FATAL,
                    false,
                    false,
                    false,
                    AccountFailureScope.REQUEST,
                    Duration.ZERO,
                    false
                );
            }
            boolean retryable = isRecoverableTransportFailure(error);
            return new Classification(
                failure,
                null,
                retryable ? AccountPoolService.UpstreamErrorType.RECOVERABLE : AccountPoolService.UpstreamErrorType.FATAL,
                retryable,
                retryable,
                retryable,
                retryable ? AccountFailureScope.TRANSPORT : AccountFailureScope.UNKNOWN,
                retryable ? Duration.ofMinutes(1) : Duration.ZERO,
                false
            );
        }

        int status = upstream.getStatusCode();
        String message = normalizedMessage(upstream);
        boolean openAi = account != null && (AccountPlatforms.isOpenAi(account) || AccountPlatforms.isDeepSeek(account));
        boolean anthropic = account != null && AccountPlatforms.isAnthropicApiKey(account);
        Classification policy = switch (status) {
            case 400, 404, 405, 406, 411, 413, 414, 415, 422, 431 -> classification(
                failure, status, AccountFailureScope.REQUEST, false, false, Duration.ZERO, false
            );
            case 401 -> credentialFailure(failure, status, message);
            case 402 -> classification(
                failure, status, AccountFailureScope.CAPACITY_ACCOUNT, true, true, Duration.ofMinutes(10), false
            );
            case 403 -> forbiddenFailure(failure, status, message, anthropic);
            case 408, 409 -> classification(
                failure, status, AccountFailureScope.TRANSPORT, true, true, Duration.ofMinutes(1), false
            );
            case 429 -> rateLimitFailure(failure, status, message, openAi, anthropic);
            case 500, 501, 503, 504, 529 -> classification(
                failure, status, AccountFailureScope.PROVIDER_POOL, true, true, Duration.ofMinutes(1), false
            );
            case 502 -> classification(
                failure,
                status,
                upstream.isRecoverable() ? AccountFailureScope.TRANSPORT : AccountFailureScope.PROVIDER_POOL,
                upstream.isRecoverable(),
                upstream.isRecoverable(),
                Duration.ofMinutes(1),
                false
            );
            default -> classification(
                failure,
                status,
                upstream.isRecoverable() ? AccountFailureScope.PROVIDER_POOL : AccountFailureScope.UNKNOWN,
                upstream.isRecoverable(),
                upstream.isRecoverable(),
                Duration.ofMinutes(1),
                false
            );
        };
        return policy;
    }

    private static boolean isModelNotServed(UpstreamException error) {
        String message = normalizedMessage(error).replace('_', ' ').replace('-', ' ');
        return MODEL_NOT_SERVED.stream().anyMatch(message::contains);
    }

    /** A failure of a relay: never counted by the old circuit breaker, which tiered requests are not subject to. */
    private static Classification relayClassification(
        Throwable failure,
        int statusCode,
        AccountFailureScope scope,
        boolean retryable,
        boolean crossAccountRetryAllowed,
        Duration cooldown,
        RelayFailure relayFailure
    ) {
        return new Classification(
            failure,
            statusCode,
            retryable ? AccountPoolService.UpstreamErrorType.RECOVERABLE : AccountPoolService.UpstreamErrorType.FATAL,
            retryable,
            crossAccountRetryAllowed,
            false,
            scope,
            cooldown,
            false,
            relayFailure
        );
    }

    private static Classification credentialFailure(Throwable failure, int status, String message) {
        boolean permanent = containsAny(
            message,
            "token_revoked",
            "token revoked",
            "token_invalidated",
            "organization disabled",
            "account disabled",
            "credential has been revoked"
        );
        return classification(
            failure,
            status,
            AccountFailureScope.CREDENTIAL,
            true,
            permanent,
            Duration.ofMinutes(10),
            permanent
        );
    }

    private static Classification forbiddenFailure(
        Throwable failure,
        int status,
        String message,
        boolean anthropic
    ) {
        if (containsAny(
            message,
            "token_revoked",
            "token revoked",
            "organization disabled",
            "account disabled",
            "access permanently revoked"
        )) {
            return classification(
                failure,
                status,
                AccountFailureScope.CREDENTIAL,
                true,
                true,
                Duration.ZERO,
                true
            );
        }
        if (containsAny(message, "<html", "<!doctype", "cloudflare", "cf-ray", "proxy authentication")) {
            return classification(
                failure, status, AccountFailureScope.PROXY_EDGE, true, true, Duration.ofMinutes(15), false
            );
        }
        if (containsAny(message, "unsupported country", "unsupported region", "region is not supported")) {
            return classification(
                failure, status, AccountFailureScope.REGIONAL_POLICY, true, true, Duration.ofMinutes(15), false
            );
        }
        if (containsAny(
            message,
            "model_not_found",
            "model not found",
            "does not exist or you do not have access",
            "do not have access to the model",
            "does not have access to model",
            "not allowed to use this model",
            "not allowed to use model",
            "access to this model",
            "unsupported model",
            "model is not supported"
        )) {
            return classification(failure, status, AccountFailureScope.REQUEST, true, true, Duration.ZERO, false);
        }
        Duration quarantine = anthropic ? Duration.ofMinutes(15) : Duration.ofMinutes(10);
        return classification(failure, status, AccountFailureScope.UNKNOWN, true, true, quarantine, false);
    }

    private static Classification rateLimitFailure(
        Throwable failure,
        int status,
        String message,
        boolean openAi,
        boolean anthropic
    ) {
        AccountFailureScope scope = AccountFailureScope.CAPACITY_ACCOUNT;
        if (openAi && containsAny(message, "team_model", "team model", "model rate limit", "model_limit")) {
            scope = AccountFailureScope.CAPACITY_MODEL;
        } else if (openAi && containsAny(message, "endpoint", "responses api", "chat completions")) {
            scope = AccountFailureScope.CAPACITY_ENDPOINT;
        } else if (anthropic && containsAny(message, "fable", "model rate limit", "model_limit")) {
            scope = AccountFailureScope.CAPACITY_MODEL;
        }
        Duration cooldown = retryAfter(message);
        return classification(failure, status, scope, true, true, cooldown, false);
    }

    private static Duration retryAfter(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "(?:retry[_ -]?after|reset[_ -]?after)[^0-9]{0,12}([0-9]{1,6})",
            java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(message);
        if (!matcher.find()) {
            return DEFAULT_RATE_LIMIT_COOLDOWN;
        }
        try {
            return Duration.ofSeconds(Math.max(1, Math.min(Integer.parseInt(matcher.group(1)), 86_400)));
        } catch (NumberFormatException ignored) {
            return DEFAULT_RATE_LIMIT_COOLDOWN;
        }
    }

    private static Classification classification(
        Throwable failure,
        int statusCode,
        AccountFailureScope scope,
        boolean retryable,
        boolean crossAccountRetryAllowed,
        Duration cooldown,
        boolean permanentCredentialFailure
    ) {
        AccountPoolService.UpstreamErrorType type = permanentCredentialFailure
            ? AccountPoolService.UpstreamErrorType.FATAL
            : retryable ? AccountPoolService.UpstreamErrorType.RECOVERABLE : AccountPoolService.UpstreamErrorType.FATAL;
        boolean circuitFailure = AccountPoolService.isCircuitBreakerFailure(type, statusCode);
        return new Classification(
            failure,
            statusCode,
            type,
            retryable,
            crossAccountRetryAllowed,
            circuitFailure,
            scope,
            cooldown,
            permanentCredentialFailure
        );
    }

    private static boolean isRecoverableTransportFailure(Throwable error) {
        boolean transportEnvelope = false;
        boolean recoverableSignal = false;
        boolean tlsFailure = false;
        Throwable current = error;
        while (current != null) {
            boolean timeout = current instanceof TimeoutException
                || current.getClass().getSimpleName().endsWith("TimeoutException");
            if (timeout) {
                recoverableSignal = true;
            } else if (current instanceof SSLException ssl) {
                tlsFailure = true;
                recoverableSignal |= isConnectionTermination(ssl.getMessage());
            } else if (current instanceof IOException) {
                recoverableSignal = true;
            }
            if (current instanceof WebClientRequestException) {
                transportEnvelope = true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return recoverableSignal || (transportEnvelope && !tlsFailure);
    }

    /** True when the failure is Netty's response/read timeout, i.e. the request had already been sent. */
    static boolean isResponseTimeoutAfterDispatch(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getSimpleName();
            if (name.equals("ReadTimeoutException") || name.equals("WriteTimeoutException")) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /**
     * True when the failure happened before the request could have reached the upstream (no route, refused or
     * timed-out connect, failed TLS handshake, exhausted connection pool), so nothing was generated or billed.
     */
    static boolean neverReachedUpstream(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RelayUnreachedException) {
                return true;
            }
            if (current instanceof EgressException egress) {
                // The route knows whether the request was written; what its transport reported underneath does not matter.
                return !egress.requestMayHaveBeenSent();
            }
            String name = current.getClass().getSimpleName();
            if (current instanceof java.net.ConnectException
                || current instanceof java.net.UnknownHostException
                || current instanceof java.net.NoRouteToHostException
                || name.equals("ConnectTimeoutException")
                || name.startsWith("PoolAcquire")
                || name.equals("SslHandshakeTimeoutException")
                || current instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static boolean isConnectionTermination(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("connection reset")
            || normalized.contains("broken pipe")
            || normalized.contains("connection closed")
            || normalized.contains("closed channel");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
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

    private static Throwable rootFailure(Throwable error) {
        return EgressException.rootCause(error);
    }

    /**
     * The failure of a transport that proves the request never left, handed on without the transport's words (design
     * 7.11): the message of netty's connect timeout holds the host and the address of the relay, a certificate error the
     * host, and the facade shows the innermost cause of an error to the client and writes it to the request log. The text
     * is fixed and there is no cause. What the original came to is kept: the failure classification gives this the
     * verdict it gave the original, and it still proves that nothing was sent.
     */
    static final class RelayUnreachedException extends RuntimeException {

        private final Classification original;

        private RelayUnreachedException(Classification original) {
            super("The source could not be reached");
            this.original = original;
        }

        Classification classification() {
            return new Classification(
                this, original.statusCode(), original.type(), original.retryable(), original.crossAccountRetryAllowed(),
                original.circuitFailure(), original.scope(), original.cooldown(), original.permanentCredentialFailure(),
                original.relayFailure()
            );
        }
    }

    /** {@code error}, a failure that proves the request never left, as one with a fixed text that is classified as it was. */
    static Throwable unreached(Throwable error, ProxyAccountEntity account) {
        Classification original = classify(error, account);
        // The original is kept without its failure, which is the error that holds the words.
        return new RelayUnreachedException(new Classification(
            null, original.statusCode(), original.type(), original.retryable(), original.crossAccountRetryAllowed(),
            original.circuitFailure(), original.scope(), original.cooldown(), original.permanentCredentialFailure(),
            original.relayFailure()
        ));
    }

    /** The failures that only a cross-protocol attempt has (design 7.8). */
    enum RelayFailure {
        /** The relay may have generated the answer: the exchange broke or ran out of time once the request was written. */
        INTERRUPTED,
        /** The relay does not serve the mapped model. */
        MODEL_NOT_SERVED,
        /** The prompt is longer than the relay's context window. */
        CONTEXT_EXCEEDED,
        /** The relay refused the converted request. */
        CONVERSION_REJECTED
    }

    /**
     * @param relayFailure what a cross-protocol attempt failed with, or null for every other failure
     */
    record Classification(
        Throwable failure,
        Integer statusCode,
        AccountPoolService.UpstreamErrorType type,
        boolean retryable,
        boolean crossAccountRetryAllowed,
        boolean circuitFailure,
        AccountFailureScope scope,
        Duration cooldown,
        boolean permanentCredentialFailure,
        RelayFailure relayFailure
    ) {

        Classification(
            Throwable failure,
            Integer statusCode,
            AccountPoolService.UpstreamErrorType type,
            boolean retryable,
            boolean crossAccountRetryAllowed,
            boolean circuitFailure,
            AccountFailureScope scope,
            Duration cooldown,
            boolean permanentCredentialFailure
        ) {
            this(failure, statusCode, type, retryable, crossAccountRetryAllowed, circuitFailure, scope, cooldown,
                permanentCredentialFailure, null);
        }

        /** The relay may be generating the answer: not retried, not rechecked, and the tier is not climbed back from it. */
        boolean interrupted() {
            return relayFailure == RelayFailure.INTERRUPTED;
        }

        /**
         * The request cannot be answered by another source of the kind that failed, but may by a native one: a relay
         * that could not take the converted request or its length says nothing about a tier whose first source is an
         * ordinary account, which gets the request in its own form. Only the tiers that start with a native source are
         * a way out, and only one crossing is made.
         */
        boolean crossTierOnly() {
            return relayFailure == RelayFailure.CONTEXT_EXCEEDED || relayFailure == RelayFailure.CONVERSION_REJECTED;
        }
    }
}
