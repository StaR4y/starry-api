package tech.starryapi.admin;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class DataTransferController implements DataTransferApi {

    private static final MediaType DUMP_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM;
    private static final MediaType ZIP_MEDIA_TYPE = MediaType.valueOf("application/zip");

    private final AdminAuth adminAuth;
    private final DataTransferService service;
    private final DataManagementSecurityService securityService;

    public DataTransferController(
        AdminAuth adminAuth,
        DataTransferService service,
        DataManagementSecurityService securityService
    ) {
        this.adminAuth = adminAuth;
        this.service = service;
        this.securityService = securityService;
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.DataTransferStatusResponse>> status(ServerWebExchange exchange) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.requireDatabaseAdmin(principal)
            .then(service.status())
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.DataManagementSecurityStatusResponse>> securityStatus(
        ServerWebExchange exchange
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.status(principal).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.InitializeDataManagementSecurityResponse>> initializeSecurity(
        ServerWebExchange exchange
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.initialize(principal, exchange).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.UnlockDataManagementResponse>> unlockSecurity(
        ServerWebExchange exchange,
        DataTransferDtos.UnlockDataManagementRequest request
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.unlock(
            principal,
            exchange,
            request.securityKey(),
            request.action(),
            request.target()
        ).map(ApiResponse::ok);
    }

    @Override
    public Mono<ResponseEntity<Flux<DataBuffer>>> exportDatabase(
        ServerWebExchange exchange,
        String securityToken
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.authorize(
                principal,
                exchange,
                securityToken,
                DataManagementSecurityService.Action.EXPORT_DATABASE,
                null,
                false
            )
            .then(service.exportDatabase())
            .map(file -> download(exchange, file, DUMP_MEDIA_TYPE));
    }

    @Override
    public Mono<ResponseEntity<Flux<DataBuffer>>> exportBackup(
        ServerWebExchange exchange,
        String securityToken
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.authorize(
                principal,
                exchange,
                securityToken,
                DataManagementSecurityService.Action.EXPORT_BACKUP,
                null,
                false
            )
            .then(service.exportBackup())
            .map(file -> download(exchange, file, ZIP_MEDIA_TYPE));
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.BackupListResponse>> backups(ServerWebExchange exchange) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.requireDatabaseAdmin(principal)
            .then(service.backups())
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.BackupCreateResponse>> createBackup(
        ServerWebExchange exchange,
        String securityToken
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.authorize(
                principal,
                exchange,
                securityToken,
                DataManagementSecurityService.Action.CREATE_BACKUP,
                null,
                false
            )
            .then(service.createBackup())
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadBackup(
        ServerWebExchange exchange,
        @PathVariable String fileName,
        String securityToken
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.authorize(
                principal,
                exchange,
                securityToken,
                DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
                fileName,
                false
            )
            .then(service.downloadBackup(fileName))
            .map(file -> download(exchange, file, ZIP_MEDIA_TYPE));
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.DeleteBackupResponse>> deleteBackup(
        ServerWebExchange exchange,
        @PathVariable String fileName,
        String securityToken
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.authorize(
                principal,
                exchange,
                securityToken,
                DataManagementSecurityService.Action.DELETE_BACKUP,
                fileName,
                true
            )
            .then(service.deleteBackup(fileName))
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.ImportResultResponse>> importBackup(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file,
        @RequestParam("confirm") String confirm,
        String securityToken
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return securityService.authorize(
                principal,
                exchange,
                securityToken,
                DataManagementSecurityService.Action.RESTORE_DATABASE,
                null,
                true
            )
            .then(service.importBackup(file, confirm))
            .map(ApiResponse::ok);
    }

    private ResponseEntity<Flux<DataBuffer>> download(
        ServerWebExchange exchange,
        DataTransferService.ExportFile file,
        MediaType mediaType
    ) {
        Flux<DataBuffer> body = DataBufferUtils
            .read(file.path(), exchange.getResponse().bufferFactory(), 64 * 1024)
            .doFinally(signal -> file.cleanup());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .contentLength(file.sizeBytes())
            .contentType(mediaType)
            .body(body);
    }
}
