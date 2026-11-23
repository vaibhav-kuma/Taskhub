package com.taskhub.desktop.api;

import java.util.List;

/**
 * Thrown for every non-2xx response and for transport failures.
 *
 * <p>{@link #code()} is the stable server contract ({@code VALIDATION_FAILED},
 * {@code TOKEN_EXPIRED}, ...). {@code status == 0} means the request never reached the server.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String requestId;
    private final List<FieldError> fieldErrors;

    public ApiException(int status, String code, String message, String requestId,
                        List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.requestId = requestId;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, null, List.of());
    }

    public ApiException(String code, String message) {
        this(0, code, message, null, List.of());
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public boolean isUnauthorized() {
        return status == 401;
    }

    public boolean isNetworkError() {
        return status == 0;
    }

    /** Human-readable one-liner for toasts. */
    public String displayMessage() {
        if (fieldErrors.isEmpty()) {
            return getMessage();
        }
        return getMessage() + " (" + String.join("; ",
                fieldErrors.stream().map(e -> e.field() + ": " + e.message()).toList()) + ")";
    }

    public record FieldError(String field, String message) {
    }
}
