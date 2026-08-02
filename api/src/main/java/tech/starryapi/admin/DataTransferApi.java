package tech.starryapi.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/data-transfer")
public interface DataTransferApi {

    @GetMapping("/status")
    Mono<ApiResponse<DataTransferDtos.DataTransferStatusResponse>> status(ServerWebExchange exchange);

    @GetMapping("/database.dump")
    Mono<ResponseEntity<Flux<DataBuffer>>> exportDatabase(ServerWebExchange exchange);

    @GetMapping("/backup.zip")
    Mono<ResponseEntity<Flux<DataBuffer>>> exportBackup(ServerWebExchange exchange);

    @GetMapping("/backups")
    Mono<ApiResponse<DataTransferDtos.BackupListResponse>> backups(ServerWebExchange exchange);

    @PostMapping("/backups")
    Mono<ApiResponse<DataTransferDtos.BackupCreateResponse>> createBackup(ServerWebExchange exchange);

    @GetMapping("/backups/{fileName:.+}")
    Mono<ResponseEntity<Flux<DataBuffer>>> downloadBackup(ServerWebExchange exchange, @PathVariable String fileName);

    @DeleteMapping("/backups/{fileName:.+}")
    Mono<ApiResponse<DataTransferDtos.DeleteBackupResponse>> deleteBackup(ServerWebExchange exchange, @PathVariable String fileName);

    @PostMapping("/import")
    Mono<ApiResponse<DataTransferDtos.ImportResultResponse>> importBackup(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file,
        @RequestParam("confirm") String confirm
    );
}
