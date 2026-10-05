package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.IdempotencyPayloadMismatchException;
import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.PaymentNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapeia exceptions do domínio em respostas HTTP semânticas.
 *
 * <p>C7 Fix: Trata ObjectOptimisticLockingFailureException retornando HTTP 409 Conflict.
 * C8 Fix: Trata IdempotencyPayloadMismatchException retornando HTTP 422 Unprocessable Entity.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(PaymentNotFoundException ex) {
        log.debug("payment not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "payment_not_found", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        log.info("idempotency conflict: key={}", ex.idempotencyKey());
        Map<String, Object> body = baseBody(HttpStatus.CONFLICT, "idempotency_conflict", ex.getMessage());
        body.put("idempotencyKey", ex.idempotencyKey());
        body.put("hint", "retry with exponential backoff, then GET /payments/{id} to resolve state");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ResponseEntity<Map<String, Object>> handlePayloadMismatch(IdempotencyPayloadMismatchException ex) {
        log.warn("idempotency payload mismatch: {}", ex.getMessage());
        Map<String, Object> body = baseBody(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused", ex.getMessage());
        body.put("idempotencyKey", ex.idempotencyKey());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(InvalidPaymentTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransition(InvalidPaymentTransitionException ex) {
        log.debug("invalid payment transition: {}", ex.getMessage());
        Map<String, Object> body = baseBody(HttpStatus.CONFLICT, "invalid_transition", ex.getMessage());
        body.put("paymentId", ex.paymentId());
        body.put("from", ex.from().name());
        body.put("to", ex.to().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception ex) {
        log.warn("optimistic lock failure during concurrent modification: {}", ex.getMessage());
        Map<String, Object> body = baseBody(HttpStatus.CONFLICT, "concurrent_modification",
            "The resource was modified concurrently by another transaction. Please retry.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getAllValidationResults().stream()
            .flatMap(vr -> vr.getResolvableErrors().stream())
            .map(err -> err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return error(HttpStatus.BAD_REQUEST, "missing_header",
            "required header '" + ex.getHeaderName() + "' is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "invalid_argument",
            "parameter '" + ex.getName() + "' has invalid value");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "malformed_body", "request body is malformed or missing");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "invalid_argument", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        log.error("unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "an unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(baseBody(status, code, message));
    }

    private Map<String, Object> baseBody(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}