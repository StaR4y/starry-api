package tech.starryapi.ticket;

import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.ticket.TicketDtos.*;

@RequestMapping("/tickets")
public interface TicketApi {
    @GetMapping
    Mono<ApiResponse<Page>> list(ServerWebExchange exchange,
        @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize);
    @GetMapping("/{id}")
    Mono<ApiResponse<ThreadPage>> thread(ServerWebExchange exchange, @PathVariable String id,
        @RequestParam(defaultValue = "0") long before);
    @PostMapping(consumes = "multipart/form-data")
    Mono<ApiResponse<Ticket>> create(ServerWebExchange exchange, @RequestPart("request") Mono<Submission> request,
        @RequestPart(value = "files", required = false) Flux<FilePart> files);
    @PostMapping(value = "/{id}/replies", consumes = "multipart/form-data")
    Mono<ApiResponse<Ticket>> reply(ServerWebExchange exchange, @PathVariable String id,
        @RequestPart("request") Mono<Submission> request, @RequestPart(value = "files", required = false) Flux<FilePart> files);
    @PatchMapping("/{id}/status")
    Mono<ApiResponse<Ticket>> status(ServerWebExchange exchange, @PathVariable String id, @RequestBody StatusUpdate request);
    @GetMapping("/{id}/attachments/{attachmentId}")
    Mono<ResponseEntity<byte[]>> download(ServerWebExchange exchange, @PathVariable String id, @PathVariable String attachmentId);
}
