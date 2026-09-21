package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;

class DataTransferControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsDumpAndDeletesTemporaryExportAfterCompletion() throws Exception {
        byte[] content = "postgresql custom dump".getBytes(StandardCharsets.UTF_8);
        Path dump = tempDir.resolve("database.dump");
        Files.write(dump, content);

        AdminAuth adminAuth = mock(AdminAuth.class);
        DataTransferService service = mock(DataTransferService.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        AdminPrincipal principal = new AdminPrincipal(
            "admin-1",
            "admin",
            "ADMIN",
            java.time.Instant.now().plusSeconds(60),
            false
        );
        when(exchange.getResponse()).thenReturn(response);
        when(response.bufferFactory()).thenReturn(DefaultDataBufferFactory.sharedInstance);
        when(adminAuth.verifyAdmin(exchange)).thenReturn(principal);
        when(securityService.authorize(
            principal,
            exchange,
            "grant",
            DataManagementSecurityService.Action.EXPORT_DATABASE,
            null,
            false
        )).thenReturn(Mono.empty());
        when(service.exportDatabase()).thenReturn(Mono.just(
            new DataTransferService.ExportFile("database.dump", dump, content.length, true)
        ));

        ResponseEntity<Flux<DataBuffer>> download = new DataTransferController(adminAuth, service, securityService)
            .exportDatabase(exchange, "grant")
            .block();

        assertThat(download).isNotNull();
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(download.getHeaders().getContentLength()).isEqualTo(content.length);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("database.dump");
        assertThat(read(download.getBody())).isEqualTo(content);
        assertThat(dump).doesNotExist();
        verify(adminAuth).verifyAdmin(exchange);
        verify(securityService).authorize(
            principal,
            exchange,
            "grant",
            DataManagementSecurityService.Action.EXPORT_DATABASE,
            null,
            false
        );
    }

    @Test
    void bindsEveryProtectedOperationToTheExpectedActionTargetAndConsumptionRule() {
        AdminAuth adminAuth = mock(AdminAuth.class);
        DataTransferService service = mock(DataTransferService.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        AdminPrincipal principal = new AdminPrincipal(
            "admin-1",
            "admin",
            "ADMIN",
            java.time.Instant.now().plusSeconds(60),
            false
        );
        RuntimeException downstream = new RuntimeException("stop after authorization");
        when(adminAuth.verifyAdmin(exchange)).thenReturn(principal);
        stubAuthorization(
            securityService,
            principal,
            exchange,
            "export-grant",
            DataManagementSecurityService.Action.EXPORT_BACKUP,
            null,
            false
        );
        stubAuthorization(
            securityService,
            principal,
            exchange,
            "create-grant",
            DataManagementSecurityService.Action.CREATE_BACKUP,
            null,
            false
        );
        stubAuthorization(
            securityService,
            principal,
            exchange,
            "download-grant",
            DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
            "backup.zip",
            false
        );
        stubAuthorization(
            securityService,
            principal,
            exchange,
            "delete-grant",
            DataManagementSecurityService.Action.DELETE_BACKUP,
            "backup.zip",
            true
        );
        stubAuthorization(
            securityService,
            principal,
            exchange,
            "restore-grant",
            DataManagementSecurityService.Action.RESTORE_DATABASE,
            null,
            true
        );
        when(service.exportBackup()).thenReturn(Mono.error(downstream));
        when(service.createBackup()).thenReturn(Mono.error(downstream));
        when(service.downloadBackup("backup.zip")).thenReturn(Mono.error(downstream));
        when(service.deleteBackup("backup.zip")).thenReturn(Mono.error(downstream));
        Mono<FilePart> upload = Mono.just(mock(FilePart.class));
        when(service.importBackup(upload, DataTransferService.IMPORT_CONFIRMATION)).thenReturn(Mono.error(downstream));
        DataTransferController controller = new DataTransferController(adminAuth, service, securityService);

        assertStopsAfterAuthorization(() -> controller.exportBackup(exchange, "export-grant").block(), downstream);
        assertStopsAfterAuthorization(() -> controller.createBackup(exchange, "create-grant").block(), downstream);
        assertStopsAfterAuthorization(() -> controller.downloadBackup(
            exchange,
            "backup.zip",
            "download-grant"
        ).block(), downstream);
        assertStopsAfterAuthorization(() -> controller.deleteBackup(
            exchange,
            "backup.zip",
            "delete-grant"
        ).block(), downstream);
        assertStopsAfterAuthorization(() -> controller.importBackup(
            exchange,
            upload,
            DataTransferService.IMPORT_CONFIRMATION,
            "restore-grant"
        ).block(), downstream);

        verifyAuthorization(
            securityService,
            principal,
            exchange,
            "export-grant",
            DataManagementSecurityService.Action.EXPORT_BACKUP,
            null,
            false
        );
        verifyAuthorization(
            securityService,
            principal,
            exchange,
            "create-grant",
            DataManagementSecurityService.Action.CREATE_BACKUP,
            null,
            false
        );
        verifyAuthorization(
            securityService,
            principal,
            exchange,
            "download-grant",
            DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
            "backup.zip",
            false
        );
        verifyAuthorization(
            securityService,
            principal,
            exchange,
            "delete-grant",
            DataManagementSecurityService.Action.DELETE_BACKUP,
            "backup.zip",
            true
        );
        verifyAuthorization(
            securityService,
            principal,
            exchange,
            "restore-grant",
            DataManagementSecurityService.Action.RESTORE_DATABASE,
            null,
            true
        );
    }

    private void stubAuthorization(
        DataManagementSecurityService securityService,
        AdminPrincipal principal,
        ServerWebExchange exchange,
        String token,
        DataManagementSecurityService.Action action,
        String target,
        boolean consume
    ) {
        when(securityService.authorize(principal, exchange, token, action, target, consume))
            .thenReturn(Mono.empty());
    }

    private void verifyAuthorization(
        DataManagementSecurityService securityService,
        AdminPrincipal principal,
        ServerWebExchange exchange,
        String token,
        DataManagementSecurityService.Action action,
        String target,
        boolean consume
    ) {
        verify(securityService).authorize(principal, exchange, token, action, target, consume);
    }

    private void assertStopsAfterAuthorization(Runnable operation, RuntimeException downstream) {
        assertThatThrownBy(operation::run).isSameAs(downstream);
    }

    private byte[] read(Flux<DataBuffer> body) {
        DataBuffer joined = DataBufferUtils.join(body).block();
        assertThat(joined).isNotNull();
        try {
            byte[] bytes = new byte[joined.readableByteCount()];
            joined.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(joined);
        }
    }
}
