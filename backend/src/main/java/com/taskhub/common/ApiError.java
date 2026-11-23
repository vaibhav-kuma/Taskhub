package com.taskhub.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Stable machine-readable error contract shared by every failed request.
 *
 * <p>{@code code} is the contract: clients branch on it, never on {@code message} text.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(name = "ApiError", description = "Standard error payload returned for every non-2xx response")
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String requestId,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path, String requestId) {
        return new ApiError(Instant.now(), status, code, message, path, requestId, List.of());
    }

    public static ApiError of(int status, String code, String message, String path, String requestId,
                              List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status, code, message, path, requestId, fieldErrors);
    }
}
