package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
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
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(exchange.getResponse()).thenReturn(response);
        when(response.bufferFactory()).thenReturn(DefaultDataBufferFactory.sharedInstance);
        when(service.exportDatabase()).thenReturn(Mono.just(
            new DataTransferService.ExportFile("database.dump", dump, content.length, true)
        ));

        ResponseEntity<Flux<DataBuffer>> download = new DataTransferController(adminAuth, service)
            .exportDatabase(exchange)
            .block();

        assertThat(download).isNotNull();
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(download.getHeaders().getContentLength()).isEqualTo(content.length);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("database.dump");
        assertThat(read(download.getBody())).isEqualTo(content);
        assertThat(dump).doesNotExist();
        verify(adminAuth).verifyAdmin(exchange);
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
