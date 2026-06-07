package xyz.star4y.api.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class ProxyController implements ProxyApi {

    private final ProxyFacade facade;
    private final ProxyModelService modelService;

    public ProxyController(ProxyFacade facade, ProxyModelService modelService) {
        this.facade = facade;
        this.modelService = modelService;
    }

    @Override
    public Mono<ResponseEntity<?>> health() {
        return Mono.just(ResponseEntity.ok(modelService.health()));
    }

    @Override
    public Mono<ResponseEntity<?>> chat(@RequestBody JsonNode request, ServerWebExchange exchange) {
        return facade.chat(request, exchange);
    }

    @Override
    public Mono<ResponseEntity<?>> responses(@RequestBody JsonNode request, ServerWebExchange exchange) {
        return facade.responses(request, exchange);
    }

    @Override
    public Mono<ResponseEntity<?>> messages(@RequestBody JsonNode request, ServerWebExchange exchange) {
        return facade.messages(request, exchange);
    }

    @Override
    public Mono<ResponseEntity<?>> geminiGenerateContent(@PathVariable String model, @RequestBody JsonNode request, ServerWebExchange exchange) {
        return facade.geminiGenerateContent(model, request, exchange);
    }
}
