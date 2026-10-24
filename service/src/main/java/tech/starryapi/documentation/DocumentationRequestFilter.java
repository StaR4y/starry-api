package tech.starryapi.documentation;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.*;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class DocumentationRequestFilter implements WebFilter {
    private final AdminAuth auth;
    private final ObjectMapper json;
    private final Semaphore uploads = new Semaphore(2);
    public DocumentationRequestFilter(AdminAuth auth, ObjectMapper json) { this.auth = auth; this.json = json; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!(path.equals("/documentation") || path.startsWith("/documentation/") || path.startsWith("/admin/documentation/"))) return chain.filter(exchange);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (path.startsWith("/documentation/media/")) return chain.filter(exchange);
        try {
            if (path.startsWith("/admin/")) auth.verifyAdmin(exchange); else auth.verify(exchange);
        } catch (ApiException error) { return reject(exchange, error.getStatus(), error.getCode(), error.getMessage()); }
        boolean upload = path.matches("/admin/documentation/pages/[^/]+/attachments") && exchange.getRequest().getMethod() == HttpMethod.POST;
        long limit = upload ? DocumentationStorage.REQUEST_LIMIT : 1024 * 1024;
        if (exchange.getRequest().getHeaders().getContentLength() > limit) return reject(exchange, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "附件最大 64 MB");
        if (upload && !uploads.tryAcquire()) return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "DOCUMENTATION_BUSY", "上传繁忙，请稍后重试");
        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override public Flux<DataBuffer> getBody() {
                AtomicLong bytes = new AtomicLong();
                return super.getBody().<DataBuffer>handle((buffer, sink) -> {
                    if (bytes.addAndGet(buffer.readableByteCount()) > limit) {
                        DataBufferUtils.release(buffer);
                        sink.error(new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "文档请求过大"));
                    } else sink.next(buffer);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
            }
        };
        return Mono.defer(() -> chain.filter(exchange.mutate().request(request).build()))
            .doFinally(signal -> { if (upload) uploads.release(); });
    }
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try { return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(json.writeValueAsBytes(ApiResponse.error(code, message))))); }
        catch (Exception e) { return Mono.error(e); }
    }
}
