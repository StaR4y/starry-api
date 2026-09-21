package tech.starryapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void keepsMissingStaticResourcesAsNotFound() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatus(
            new NoResourceFoundException("missing.js")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().error().message()).contains("missing.js");
    }

    @Test
    void suppliesAStableMessageWhenAFrameworkStatusHasNoReason() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatus(
            new ResponseStatusException(HttpStatus.NOT_FOUND)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().error().message()).isEqualTo("Not Found");
    }

    @Test
    void mapsDataBufferLimitToPayloadTooLarge() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataBufferLimit(
            new DataBufferLimitException("Exceeded limit")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(response.getBody().error().message()).isEqualTo("上传文件过大");
    }
}
