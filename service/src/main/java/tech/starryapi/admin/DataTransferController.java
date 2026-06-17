package tech.starryapi.admin;

import java.nio.charset.StandardCharsets;
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
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class DataTransferController implements DataTransferApi {

    private static final MediaType SQL_MEDIA_TYPE = new MediaType("application", "sql", StandardCharsets.UTF_8);
    private static final MediaType ZIP_MEDIA_TYPE = MediaType.valueOf("application/zip");

    private final AdminAuth adminAuth;
    private final DataTransferService service;

    public DataTransferController(AdminAuth adminAuth, DataTransferService service) {
        this.adminAuth = adminAuth;
        this.service = service;
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.DataTransferStatusResponse>> status(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.status().map(ApiResponse::ok);
    }

    @Override
    public Mono<ResponseEntity<byte[]>> exportDatabase(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.exportDatabase().map(file -> download(file, SQL_MEDIA_TYPE));
    }

    @Override
    public Mono<ResponseEntity<byte[]>> exportBackup(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.exportBackup().map(file -> download(file, ZIP_MEDIA_TYPE));
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.BackupListResponse>> backups(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.backups().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.BackupCreateResponse>> createBackup(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.createBackup().map(ApiResponse::ok);
    }

    @Override
    public Mono<ResponseEntity<byte[]>> downloadBackup(ServerWebExchange exchange, @PathVariable String fileName) {
        adminAuth.verifyAdmin(exchange);
        return service.downloadBackup(fileName).map(file -> download(file, ZIP_MEDIA_TYPE));
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.DeleteBackupResponse>> deleteBackup(
        ServerWebExchange exchange,
        @PathVariable String fileName
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.deleteBackup(fileName).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<DataTransferDtos.ImportResultResponse>> importBackup(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file,
        @RequestParam("confirm") String confirm
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.importBackup(file, confirm).map(ApiResponse::ok);
    }

    private ResponseEntity<byte[]> download(DataTransferService.ExportFile file, MediaType mediaType) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .contentLength(file.bytes().length)
            .contentType(mediaType)
            .body(file.bytes());
    }
}
