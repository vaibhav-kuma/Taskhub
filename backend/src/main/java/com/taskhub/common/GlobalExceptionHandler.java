package com.taskhub.common;

import com.taskhub.common.exception.ApiException;
import com.taskhub.config.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single translation point from exceptions to the {@link ApiError} contract.
 *
 * <p>Security rules baked in here:
 * <ul>
 *   <li>5xx never leaks exception messages or stack traces to the client.</li>
 *   <li>Database constraint messages are mapped to a generic 409 (no schema disclosure).</li>
 *   <li>Every response carries the request id so support can correlate logs.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, ServletWebRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(error(ex.getStatus().value(), ex.getCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              ServletWebRequest request) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "VALIDATION_FAILED", "Request validation failed",
                        path(request), requestId(), fields));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                         ServletWebRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(409, "CONCURRENT_MODIFICATION",
                        "The record was modified by someone else. Reload and try again.", request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    ServletWebRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(409, "DATA_INTEGRITY_VIOLATION",
                        "The request conflicts with existing data.", request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, ServletWebRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(403, "FORBIDDEN", "You do not have permission to perform this action.", request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, ServletWebRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(401, "UNAUTHENTICATED", "Authentication is required.", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, ServletWebRequest request) {
        log.error("Unhandled exception on {} {}", request.getHttpMethod(), path(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(500, "INTERNAL_ERROR", "An unexpected error occurred.", request));
    }

    /** Overrides every exception Spring MVC already knows about (400/404/405/415/...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        ApiError payload = ApiError.of(status, codeFor(status), messageFor(ex, status),
                path((ServletWebRequest) request), requestId());
        return ResponseEntity.status(statusCode).headers(headers).body(payload);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .distinct()
                .toList();
        ApiError payload = ApiError.of(400, "VALIDATION_FAILED", "Request validation failed",
                path((ServletWebRequest) request), requestId(), fields);
        return ResponseEntity.badRequest().headers(headers).body(payload);
    }

    private ApiError error(int status, String code, String message, ServletWebRequest request) {
        return ApiError.of(status, code, message, path(request), requestId());
    }

    private static String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        return id == null ? "n/a" : id;
    }

    private static String path(ServletWebRequest request) {
        return request.getRequest().getRequestURI();
    }

    private static String lastNode(String propertyPath) {
        int idx = propertyPath.lastIndexOf('.');
        return idx < 0 ? propertyPath : propertyPath.substring(idx + 1);
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 409 -> "CONFLICT";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "UNPROCESSABLE_ENTITY";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }

    private static String messageFor(Exception ex, int status) {
        if (status >= 500) {
            return "An unexpected error occurred.";
        }
        return switch (ex) {
            case org.springframework.web.servlet.resource.NoResourceFoundException e -> "Resource not found.";
            case org.springframework.web.HttpRequestMethodNotSupportedException e -> "HTTP method not supported.";
            case org.springframework.http.converter.HttpMessageNotReadableException e -> "Malformed or unreadable JSON request body.";
            case org.springframework.web.bind.MissingServletRequestParameterException e ->
                    "Required query parameter '" + e.getParameterName() + "' is missing.";
            case org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e ->
                    "Parameter '" + e.getName() + "' has an invalid value.";
            case org.springframework.web.HttpMediaTypeNotSupportedException e -> "Unsupported content type.";
            default -> ex.getMessage() == null ? "Request failed." : ex.getMessage();
        };
    }
}
