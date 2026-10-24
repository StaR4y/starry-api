package tech.starryapi.egress;

import io.netty.handler.proxy.ProxyConnectException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Only these fixed diagnostics may be exposed; transport messages can contain credentials. */
public enum EgressFailure {
    RUNTIME_DISABLED("Mihomo is disabled. Start the component and enable STARRY_EGRESS_MIHOMO_ENABLED, then restart the backend."),
    NODE_UNAVAILABLE("The selected node is disabled, missing from its subscription or unsupported."),
    NODE_NOT_ACTIVE("The selected node has not been loaded by Mihomo. Retry after the runtime is ready."),
    CONTROLLER_UNREACHABLE("Cannot reach the Mihomo controller. Check that it is running and reachable from the backend."),
    CONTROLLER_AUTH_FAILED("Mihomo controller authentication failed. Check that the controller secrets match."),
    CONFIG_REJECTED("Mihomo rejected the node configuration. Check protocol options and listener port conflicts."),
    RUNTIME_FAILED("Mihomo configuration could not be activated. Check the component status and backend logs."),
    PROBE_TIMEOUT("The exit probe timed out. Check node connectivity and access to api.ipify.org."),
    PROXY_CONNECT_FAILED("The proxy connection failed. Check the Mihomo listener and the node's upstream connectivity."),
    PROBE_DNS_FAILED("The exit probe could not resolve a hostname. Check DNS on the proxy route."),
    PROBE_TLS_FAILED("The exit probe failed TLS verification or negotiation. Check the proxy route's certificate and TLS settings."),
    PROBE_HTTP_FAILED("The exit probe service returned an unsuccessful HTTP status. This alone does not prove the node is unavailable."),
    PROBE_INVALID_RESPONSE("The exit probe service did not return a valid IP address."),
    PROBE_FAILED("The exit probe failed. Check the node and Mihomo component status."),
    // Failures of account requests (as opposed to activation and exit probes). A request failure either happened
    // before anything was sent, or after the request may have reached the proxy.
    ACCOUNT_ROUTE_UNAVAILABLE("The account's outbound route could not be loaded."),
    LEGACY_PROXY_INVALID("The account's legacy proxy URL is not a supported HTTP CONNECT or SOCKS5 URL."),
    TLS_PROFILE_UNAVAILABLE("The requested TLS profile is not enabled on the TLS component."),
    TLS_COMPONENT_UNAVAILABLE("The TLS component is disabled, not authenticated or unreachable."),
    TLS_COMPONENT_REJECTED("The TLS component rejected the request before forwarding it. Check the bridge key."),
    TLS_COMPONENT_FAILED("The TLS component could not complete the forwarded request."),
    PROXY_TRANSPORT_FAILED("The connection through the selected proxy failed after the request may have been sent."),
    PROXY_ROUTE_SUSPENDED("The selected proxy failed to connect repeatedly; its requests use other accounts for a few seconds before it is tried again.");

    private final String message;

    EgressFailure(String message) { this.message = message; }
    public String message() { return message; }

    public static EgressFailure classify(Throwable error, EgressFailure fallback) {
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            if (error instanceof EgressException e && e.failure() != null) return e.failure();
            if (error instanceof SSLException) return PROBE_TLS_FAILED;
            if (error instanceof TimeoutException || error instanceof io.netty.handler.timeout.TimeoutException
                || error instanceof io.netty.channel.ConnectTimeoutException) return PROBE_TIMEOUT;
            if (error instanceof UnknownHostException) return PROBE_DNS_FAILED;
            if (error instanceof ProxyConnectException || error instanceof ConnectException) return PROXY_CONNECT_FAILED;
            if (error instanceof WebClientResponseException) return PROBE_HTTP_FAILED;
            if (error instanceof DecodingException) return PROBE_INVALID_RESPONSE;
        }
        return fallback;
    }
}
