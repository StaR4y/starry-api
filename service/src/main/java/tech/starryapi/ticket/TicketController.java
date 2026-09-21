package tech.starryapi.ticket;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.ticket.TicketDtos.*;

@RestController
public class TicketController implements TicketApi {
    private final TicketService service;
    private final TicketAttachments attachments;
    private final AdminAuth auth;
    public TicketController(TicketService service, TicketAttachments attachments, AdminAuth auth) {
        this.service = service; this.attachments = attachments; this.auth = auth;
    }
    @Override public Mono<ApiResponse<Page>> list(ServerWebExchange exchange, String status, String search, int page, int pageSize) {
        return service.list(auth.verify(exchange), status, search, page, pageSize).map(ApiResponse::ok);
    }
    @Override public Mono<ApiResponse<ThreadPage>> thread(ServerWebExchange exchange, String id, long before) {
        return service.thread(auth.verify(exchange), id, before).map(ApiResponse::ok);
    }
    @Override public Mono<ApiResponse<Ticket>> create(ServerWebExchange exchange, Mono<Submission> request, Flux<FilePart> files) {
        var actor = auth.verify(exchange);
        return request.flatMap(input -> {
            TicketService.validateSubmission(input, true);
            return attachments.read(files).flatMap(uploads -> service.create(actor, input, uploads));
        }).map(ApiResponse::ok);
    }
    @Override public Mono<ApiResponse<Ticket>> reply(ServerWebExchange exchange, String id, Mono<Submission> request, Flux<FilePart> files) {
        var actor = auth.verify(exchange);
        return service.find(actor, id).then(request).flatMap(input -> {
            TicketService.validateSubmission(input, false);
            return attachments.read(files).flatMap(uploads -> service.reply(actor, id, input, uploads));
        }).map(ApiResponse::ok);
    }
    @Override public Mono<ApiResponse<Ticket>> status(ServerWebExchange exchange, String id, StatusUpdate request) {
        return service.changeStatus(auth.verify(exchange), id, request).map(ApiResponse::ok);
    }
    @Override public Mono<ResponseEntity<byte[]>> download(ServerWebExchange exchange, String id, String attachmentId) {
        return service.download(auth.verify(exchange), id, attachmentId).map(file -> ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.bytes().length)
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
            .body(file.bytes()));
    }
}
