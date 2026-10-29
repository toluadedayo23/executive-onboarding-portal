package ng.risktech.execplatform.common;

import jakarta.servlet.http.HttpServletRequest;
import ng.risktech.execplatform.onboarding.OnboardingValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status(), request, ex.getMessage(), ex.code(), null);
    }

    @ExceptionHandler(OnboardingValidationException.class)
    ResponseEntity<Map<String, Object>> validation(OnboardingValidationException ex, HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, request, "Validation failed", "VALIDATION_FAILED", ex.problems());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var fields = ex.getBindingResult().getFieldErrors().stream().map(e -> e.getField()).distinct().toList();
        return response(HttpStatus.BAD_REQUEST, request, "Invalid request", "INVALID_FIELDS", fields);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, request, "Invalid request", null, null);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, Object>> unauthorized(SecurityException ex, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, request, "Invalid credentials", null, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, request, "Access denied", null, null);
    }

    @ExceptionHandler({EmptyResultDataAccessException.class, NoResourceFoundException.class})
    ResponseEntity<Map<String, Object>> notFound(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, request, "Resource not found", null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, request, "Method not allowed", null, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, request, "File is larger than the 5 MB limit", "PAYLOAD_TOO_LARGE", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled exception traceId={} path={} type={} message={}", traceId, request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, request, "An unexpected error occurred", null, null, traceId);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, HttpServletRequest request, String message,
                                                         String code, Object problems) {
        return response(status, request, message, code, problems, UUID.randomUUID().toString());
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, HttpServletRequest request, String message,
                                                         String code, Object problems, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("traceId", traceId);
        if (code != null) body.put("code", code);
        if (problems != null) body.put("problems", problems);
        return ResponseEntity.status(status).body(body);
    }
}
