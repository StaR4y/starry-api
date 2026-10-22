package tech.starryapi.common;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
            .body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(WebExchangeBindException exception) {
        String message = exception.getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .orElse("Invalid request");
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        HttpStatus resolvedStatus = HttpStatus.resolve(status.value());
        String code = resolvedStatus == null ? "HTTP_" + status.value() : resolvedStatus.name();
        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = resolvedStatus == null ? code : resolvedStatus.getReasonPhrase();
        }
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }

    @ExceptionHandler(DataBufferLimitException.class)
    ResponseEntity<ApiResponse<Void>> handleDataBufferLimit(DataBufferLimitException ignored) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(ApiResponse.error("PAYLOAD_TOO_LARGE", "上传文件过大"));
    }

    @ExceptionHandler(Throwable.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Throwable throwable) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("INTERNAL_ERROR", throwable.getMessage()));
    }
}
