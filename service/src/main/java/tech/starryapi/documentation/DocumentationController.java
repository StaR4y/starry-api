package tech.starryapi.documentation;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.documentation.DocumentationDtos.*;

@RestController
public class DocumentationController {
    private final AdminAuth auth;
    private final DocumentationService service;
    private final DocumentationStorage storage;
    public DocumentationController(AdminAuth auth, DocumentationService service, DocumentationStorage storage) {
        this.auth = auth; this.service = service; this.storage = storage;
    }
    @GetMapping("/documentation")
    public Mono<ApiResponse<Catalog>> catalog(ServerWebExchange exchange, @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "zh-CN") String lang) {
        auth.verify(exchange);
        return (english(lang) ? service.catalog(q, true) : service.catalog(q)).map(ApiResponse::ok);
    }
    @GetMapping("/documentation/pages/{id}")
    public Mono<ApiResponse<Document>> read(ServerWebExchange exchange, @PathVariable String id,
        @RequestParam(defaultValue = "zh-CN") String lang) {
        auth.verify(exchange);
        return (english(lang) ? service.read(id, true) : service.read(id)).map(ApiResponse::ok);
    }
    private static boolean english(String lang) {
        return lang.equalsIgnoreCase("en") || lang.toLowerCase(java.util.Locale.ROOT).startsWith("en-");
    }
    @GetMapping("/admin/documentation/pages")
    public Mono<ApiResponse<List<AdminEntry>>> adminList(ServerWebExchange exchange) {
        auth.verifyAdmin(exchange);
        return service.listAdmin().map(ApiResponse::ok);
    }
    @GetMapping("/admin/documentation/pages/{id}")
    public Mono<ApiResponse<AdminDocument>> adminRead(ServerWebExchange exchange, @PathVariable String id) {
        auth.verifyAdmin(exchange);
        return service.readAdmin(id).map(ApiResponse::ok);
    }
    @PostMapping("/admin/documentation/pages")
    public Mono<ApiResponse<AdminDocument>> create(ServerWebExchange exchange, @Valid @RequestBody Content request) {
        var actor = auth.verifyAdmin(exchange);
        return service.create(request, actor.userId()).map(ApiResponse::ok);
    }
    @PutMapping("/admin/documentation/pages/{id}")
    public Mono<ApiResponse<AdminDocument>> save(ServerWebExchange exchange, @PathVariable String id, @Valid @RequestBody Save request) {
        var actor = auth.verifyAdmin(exchange);
        return service.save(id, request, actor.userId()).map(ApiResponse::ok);
    }
    @DeleteMapping("/admin/documentation/pages/{id}/publication")
    public Mono<ApiResponse<Boolean>> unpublish(ServerWebExchange exchange, @PathVariable String id, @Valid @RequestBody Version version) {
        var actor = auth.verifyAdmin(exchange);
        return service.withdraw(id, version.version(), false, actor.userId()).thenReturn(ApiResponse.ok(true));
    }
    @DeleteMapping("/admin/documentation/pages/{id}")
    public Mono<ApiResponse<Boolean>> delete(ServerWebExchange exchange, @PathVariable String id, @Valid @RequestBody Version version) {
        var actor = auth.verifyAdmin(exchange);
        return service.withdraw(id, version.version(), true, actor.userId()).thenReturn(ApiResponse.ok(true));
    }
    @GetMapping("/admin/documentation/settings")
    public Mono<ApiResponse<Settings>> settings(ServerWebExchange exchange) {
        auth.verifyAdmin(exchange);
        return service.settings().map(ApiResponse::ok);
    }
    @PutMapping("/admin/documentation/settings")
    public Mono<ApiResponse<Settings>> settings(ServerWebExchange exchange, @Valid @RequestBody Settings request) {
        auth.verifyAdmin(exchange);
        return service.saveSettings(request).map(ApiResponse::ok);
    }
    @PostMapping(value = "/admin/documentation/pages/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<Attachment>> upload(ServerWebExchange exchange, @PathVariable String id, @RequestPart("file") Mono<FilePart> file) {
        var actor = auth.verifyAdmin(exchange);
        return file.switchIfEmpty(Mono.error(DocumentationService.invalid("请选择附件"))).flatMap(part ->
            Mono.usingWhen(storage.store(part), stored -> service.attach(id, stored, actor.userId()),
                stored -> Mono.empty(), (stored, error) -> storage.remove(stored.id()), stored -> storage.remove(stored.id())))
            .map(ApiResponse::ok);
    }
    @DeleteMapping("/admin/documentation/pages/{id}/attachments/{file}")
    public Mono<ApiResponse<Boolean>> removeFile(ServerWebExchange exchange, @PathVariable String id, @PathVariable String file) {
        var actor = auth.verifyAdmin(exchange);
        return service.removeAttachment(id, file, actor.userId()).then(Mono.defer(() -> storage.remove(file))).thenReturn(ApiResponse.ok(true));
    }
    @GetMapping("/documentation/media/{id}")
    public Mono<ResponseEntity<Resource>> media(@PathVariable String id, @RequestParam long expires,
        @RequestParam boolean admin, @RequestParam String signature) {
        return service.media(id, expires, admin, signature).flatMap(file -> storage.resource(id).map(resource -> {
            boolean inline = file.contentType().startsWith("image/") || file.contentType().startsWith("video/");
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes()).header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Content-Disposition", (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                    .filename(file.filename(), StandardCharsets.UTF_8).build().toString()).body(resource);
        }));
    }
}
