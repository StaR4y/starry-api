package tech.starryapi.common;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
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
        LegacyStatus legacy = LEGACY_STATUS.get(status.value());
        HttpStatus resolvedStatus = HttpStatus.resolve(status.value());
        String code = legacy != null ? legacy.code()
            : resolvedStatus == null ? "HTTP_" + status.value() : resolvedStatus.name();
        String message = exception.getReason();
        if (exception instanceof NoResourceFoundException && message != null) {
            // Spring Framework 7 appends " for request '<full URL>'" (including the client-supplied Host) to this reason.
            message = REQUEST_URL_SUFFIX.matcher(message).replaceFirst(".");
        }
        if (message == null || message.isBlank()) {
            message = legacy != null ? legacy.reasonPhrase()
                : resolvedStatus == null ? code : resolvedStatus.getReasonPhrase();
        }
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }

    /**
     * Error codes and default messages are part of the API. Spring Framework 7 renamed some HttpStatus constants
     * (413 resolves to CONTENT_TOO_LARGE, 422 to UNPROCESSABLE_CONTENT) and added 421, which would silently change the
     * codes clients see; keep the ones the API has always returned.
     */
    private static final Pattern REQUEST_URL_SUFFIX = Pattern.compile(" for request '.*'\\.$");

    private static final Map<Integer, LegacyStatus> LEGACY_STATUS = Map.of(
        413, new LegacyStatus("PAYLOAD_TOO_LARGE", "Payload Too Large"),
        421, new LegacyStatus("HTTP_421", "HTTP_421"),
        422, new LegacyStatus("UNPROCESSABLE_ENTITY", "Unprocessable Entity")
    );

    private record LegacyStatus(String code, String reasonPhrase) {
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
