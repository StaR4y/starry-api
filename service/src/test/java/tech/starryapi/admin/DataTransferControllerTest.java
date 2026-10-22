package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.GlobalExceptionHandler;

class DataTransferControllerTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"/status", "/backups"})
    void metadataEndpointsRequirePageGrantBeforeReadingData(String path) {
        AdminAuth adminAuth = mock(AdminAuth.class);
        DataTransferService service = mock(DataTransferService.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        AdminPrincipal principal = new AdminPrincipal(
            "root", "admin", "ADMIN", java.time.Instant.now().plusSeconds(60), false
        );
        when(adminAuth.verifyAdmin(any())).thenReturn(principal);
        when(securityService.authorize(eq(principal), any(), isNull(),
            eq(DataManagementSecurityService.Action.VIEW_DATA_MANAGEMENT), isNull(), eq(false)))
            .thenReturn(Mono.error(new ApiException(
                HttpStatus.FORBIDDEN, "DATA_MANAGEMENT_UNLOCK_REQUIRED", "Unlock required"
            )));
        WebTestClient client = WebTestClient.bindToController(
                new DataTransferController(adminAuth, service, securityService)
            ).controllerAdvice(new GlobalExceptionHandler()).build();

        client.get().uri("/admin/data-transfer" + path).exchange()
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.error.code").isEqualTo("DATA_MANAGEMENT_UNLOCK_REQUIRED");
        verifyNoInteractions(service);

        when(securityService.authorize(eq(principal), any(), eq("page-grant"),
            eq(DataManagementSecurityService.Action.VIEW_DATA_MANAGEMENT), isNull(), eq(false)))
            .thenReturn(Mono.empty());
        when(service.status()).thenReturn(Mono.just(
            new DataTransferDtos.DataTransferStatusResponse("database", "/backups", 1024, 0, 0)
        ));
        when(service.backups()).thenReturn(Mono.just(
            new DataTransferDtos.BackupListResponse("/backups", java.util.List.of())
        ));
        client.get().uri("/admin/data-transfer" + path)
            .header(DataManagementSecurityService.SECURITY_TOKEN_HEADER, "page-grant")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.data.backupDir").isEqualTo("/backups");
    }

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
        // doFinally runs after downstream completion; wait for the asynchronous file reader's cleanup.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(dump).doesNotExist());
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
