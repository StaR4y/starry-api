package xyz.star4y.api.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public interface ProxyApi {

    @GetMapping({"/health", "/v1/health"})
    Mono<ResponseEntity<?>> health();

    @PostMapping({"/v1/chat/completions", "/chat/completions"})
    Mono<ResponseEntity<?>> chat(@RequestBody JsonNode request, ServerWebExchange exchange);

    @PostMapping({"/v1/responses", "/responses"})
    Mono<ResponseEntity<?>> responses(@RequestBody JsonNode request, ServerWebExchange exchange);

    @PostMapping({"/v1/messages", "/messages"})
    Mono<ResponseEntity<?>> messages(@RequestBody JsonNode request, ServerWebExchange exchange);

    @PostMapping({"/v1beta/models/{model}:generateContent", "/v1/models/{model}:generateContent", "/models/{model}:generateContent"})
    Mono<ResponseEntity<?>> geminiGenerateContent(
        @PathVariable String model,
        @RequestBody JsonNode request,
        ServerWebExchange exchange
    );
}
