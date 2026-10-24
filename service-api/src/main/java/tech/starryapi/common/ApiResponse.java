package tech.starryapi.common;

import java.time.Instant;

public record ApiResponse<T>(
    boolean success,
    T data,
    ApiError error,
    Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message), Instant.now());
    }

    public static ApiResponse<Object> error(String code, String message, Object data) {
        return new ApiResponse<>(false, data, new ApiError(code, message), Instant.now());
    }

    public record ApiError(String code, String message) {
    }
}
