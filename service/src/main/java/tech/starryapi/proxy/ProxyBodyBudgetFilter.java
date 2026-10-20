package tech.starryapi.proxy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Caps how many request-body bytes the proxy endpoints hold in memory at the same time.
 *
 * <p>A proxied request is buffered, parsed into a JSON tree and kept until the upstream answers, which for a
 * slow model can be minutes. Without a global budget, heap use is (concurrent requests x body size x copy
 * factor) and a burst of large contexts ends in an OutOfMemoryError that takes the whole gateway down.
 * Requests that would exceed the budget are answered immediately with 503 and Retry-After, which SDKs retry.
 */
@Component
public class ProxyBodyBudgetFilter implements WebFilter, Ordered {

    /** Charged for a request that does not declare its length (chunked uploads). */
    static final long UNKNOWN_LENGTH_COST = 1024 * 1024;
    private static final long MIN_AUTO_BUDGET = 32L * 1024 * 1024;

    private final long budgetBytes;
    private final AtomicLong inFlightBytes = new AtomicLong();

    @Autowired
    public ProxyBodyBudgetFilter(@Value("${starry.proxy.max-inflight-body-bytes:0}") long configuredBudgetBytes) {
        this(configuredBudgetBytes, Runtime.getRuntime().maxMemory());
    }

    ProxyBodyBudgetFilter(long configuredBudgetBytes, long maxHeapBytes) {
        // Auto: a quarter of the heap. The buffered body, its JSON tree and the upstream copy then stay well
        // below the heap even though one body costs several times its wire size.
        this.budgetBytes = configuredBudgetBytes > 0 ? configuredBudgetBytes : Math.max(MIN_AUTO_BUDGET, maxHeapBytes / 4);
    }

    @Override
    public int getOrder() {
        // After the maintenance gate (HIGHEST_PRECEDENCE) and the plain body-size check, before authentication.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    long inFlightBytes() {
        return inFlightBytes.get();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST || !isProxyPath(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        long declared = exchange.getRequest().getHeaders().getContentLength();
        long cost = declared >= 0 ? declared : UNKNOWN_LENGTH_COST;
        if (!tryAcquire(cost)) {
            return reject(exchange);
        }
        return chain.filter(exchange).doFinally(signal -> inFlightBytes.addAndGet(-cost));
    }

    private boolean tryAcquire(long cost) {
        while (true) {
            long current = inFlightBytes.get();
            // A lone request larger than the whole budget is still served; the per-request size limit applies to it.
            if (current > 0 && current + cost > budgetBytes) {
                return false;
            }
            if (inFlightBytes.compareAndSet(current, current + cost)) {
                return true;
            }
        }
    }

    static boolean isProxyPath(String path) {
        return path.equals("/v1/chat/completions") || path.equals("/chat/completions")
            || path.equals("/v1/responses") || path.equals("/responses")
            || path.equals("/v1/messages") || path.equals("/messages")
            || (path.endsWith(":generateContent")
                && (path.startsWith("/v1beta/models/") || path.startsWith("/v1/models/") || path.startsWith("/models/")));
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().set("Retry-After", "1");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"success\":false,\"error\":{\"code\":\"PROXY_OVERLOADED\",\"message\":"
            + "\"Too many large requests are being processed right now. Retry shortly.\"},\"timestamp\":\""
            + Instant.now() + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }
}
