package tech.starryapi.proxy;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.platform.upstream.UpstreamException;

final class UpstreamFailureClassifier {

    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(45);

    private UpstreamFailureClassifier() {
    }

    static Classification classify(Throwable error) {
        return classify(error, null);
    }

    static Classification classify(Throwable error, ProxyAccountEntity account) {
        tech.starryapi.egress.EgressException egress = findCause(error, tech.starryapi.egress.EgressException.class);
        if (egress != null) {
            return new Classification(error, null, AccountPoolService.UpstreamErrorType.RECOVERABLE,
                egress.retryable(), false, false, AccountFailureScope.REQUEST, Duration.ZERO, false);
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
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    record Classification(
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
    }
}
