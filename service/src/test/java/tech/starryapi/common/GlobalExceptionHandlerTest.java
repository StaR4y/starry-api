package tech.starryapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void keepsMissingStaticResourcesAsNotFound() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatus(
            new NoResourceFoundException(URI.create("/missing.js"), "missing.js")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().error().message()).isEqualTo("No static resource missing.js.");
    }

    @Test
    void keepsTheErrorCodesOfStatusesSpringRenamed() {
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.valueOf(413))).getBody().error())
            .satisfies(error -> {
                assertThat(error.code()).isEqualTo("PAYLOAD_TOO_LARGE");
                assertThat(error.message()).isEqualTo("Payload Too Large");
            });
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.valueOf(422), "bad")).getBody().error())
            .satisfies(error -> {
                assertThat(error.code()).isEqualTo("UNPROCESSABLE_ENTITY");
                assertThat(error.message()).isEqualTo("bad");
            });
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatusCode.valueOf(421))).getBody().error().code())
            .isEqualTo("HTTP_421");
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

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(response.getBody().error().message()).isEqualTo("上传文件过大");
    }

    @Test
    void returnsTheRecordsThatBlockAnOperationAsTheDataOfTheError() {
        ResponseEntity<? extends ApiResponse<?>> response = handler.handleApiException(new ApiException(
            HttpStatus.CONFLICT, "IN_USE", "Still in use", java.util.List.of(java.util.Map.of("accountId", "a-1"))
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("IN_USE");
        assertThat(response.getBody().data()).isEqualTo(java.util.List.of(java.util.Map.of("accountId", "a-1")));
        assertThat(handler.handleApiException(new ApiException(HttpStatus.CONFLICT, "IN_USE", "x")).getBody().data()).isNull();
    }
}
