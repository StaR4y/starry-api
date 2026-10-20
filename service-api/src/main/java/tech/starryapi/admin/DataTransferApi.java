package tech.starryapi.admin;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/data-transfer")
public interface DataTransferApi {

    @GetMapping("/status")
    Mono<ApiResponse<DataTransferDtos.DataTransferStatusResponse>> status(
        ServerWebExchange exchange,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @GetMapping("/security/status")
    Mono<ApiResponse<DataTransferDtos.DataManagementSecurityStatusResponse>> securityStatus(ServerWebExchange exchange);

    @PostMapping("/security/init")
    Mono<ApiResponse<DataTransferDtos.InitializeDataManagementSecurityResponse>> initializeSecurity(
        ServerWebExchange exchange
    );

    @PostMapping("/security/unlock")
    Mono<ApiResponse<DataTransferDtos.UnlockDataManagementResponse>> unlockSecurity(
        ServerWebExchange exchange,
        @Valid @RequestBody DataTransferDtos.UnlockDataManagementRequest request
    );

    @GetMapping("/database.dump")
    Mono<ResponseEntity<Flux<DataBuffer>>> exportDatabase(
        ServerWebExchange exchange,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @GetMapping("/backup.zip")
    Mono<ResponseEntity<Flux<DataBuffer>>> exportBackup(
        ServerWebExchange exchange,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @GetMapping("/backups")
    Mono<ApiResponse<DataTransferDtos.BackupListResponse>> backups(
        ServerWebExchange exchange,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @PostMapping("/backups")
    Mono<ApiResponse<DataTransferDtos.BackupCreateResponse>> createBackup(
        ServerWebExchange exchange,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @GetMapping("/backups/{fileName:.+}")
    Mono<ResponseEntity<Flux<DataBuffer>>> downloadBackup(
        ServerWebExchange exchange,
        @PathVariable String fileName,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @DeleteMapping("/backups/{fileName:.+}")
    Mono<ApiResponse<DataTransferDtos.DeleteBackupResponse>> deleteBackup(
        ServerWebExchange exchange,
        @PathVariable String fileName,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );

    @PostMapping("/import")
    Mono<ApiResponse<DataTransferDtos.ImportResultResponse>> importBackup(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file,
        @RequestParam("confirm") String confirm,
        @RequestHeader(name = "X-Data-Management-Token", required = false) String securityToken
    );
}
