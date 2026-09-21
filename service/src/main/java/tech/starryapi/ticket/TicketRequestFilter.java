package tech.starryapi.ticket;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TicketRequestFilter implements WebFilter {
    private final AdminAuth auth;
    private final java.util.concurrent.Semaphore submissions = new java.util.concurrent.Semaphore(4);
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public TicketRequestFilter(AdminAuth auth, com.fasterxml.jackson.databind.ObjectMapper json) { this.auth = auth; this.json = json; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!(path.equals("/tickets") || path.startsWith("/tickets/"))) return chain.filter(exchange);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        // Authenticate before multipart parsing; count streamed bytes even without Content-Length.
        try { auth.verify(exchange); }
        catch (tech.starryapi.common.ApiException error) {
            exchange.getResponse().setStatusCode(error.getStatus());
            exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            try {
                byte[] body = json.writeValueAsBytes(tech.starryapi.common.ApiResponse.error(error.getCode(), error.getMessage()));
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) { return Mono.error(e); }
        }
        long limit = 7L * 1024 * 1024;
        var tooLarge = new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "工单请求最大 7 MB");
        if (exchange.getRequest().getHeaders().getContentLength() > limit) return Mono.error(tooLarge);
        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override public Flux<DataBuffer> getBody() {
                AtomicLong bytes = new AtomicLong();
                return super.getBody().<DataBuffer>handle((buffer, sink) -> {
                    if (bytes.addAndGet(buffer.readableByteCount()) > limit) {
                        DataBufferUtils.release(buffer);
                        sink.error(tooLarge);
                    } else sink.next(buffer);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
            }
        };
        boolean submission = exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.POST;
        if (submission && !submissions.tryAcquire())
            return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "工单提交繁忙，请稍后重试"));
        return Mono.defer(() -> chain.filter(exchange.mutate().request(request).build()))
            .doFinally(signal -> { if (submission) submissions.release(); });
    }
}
