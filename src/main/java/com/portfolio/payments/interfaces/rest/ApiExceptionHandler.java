package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapeia exceptions do domínio em respostas HTTP semânticas.
 *
 * <p>Antes deste advice, qualquer RuntimeException virava 500 com stacktrace
 * exposto — vazava detalhes internos e não dava info útil ao cliente.</p>
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
        // 409 Conflict: a request raced with another using the same Idempotency-Key.
        // Cliente deve fazer GET /payments/{id} (após retry com backoff) ou re-resolver o estado.
        log.info("idempotency conflict: key={}", ex.idempotencyKey());
        Map<String, Object> body = baseBody(HttpStatus.CONFLICT, "idempotency_conflict", ex.getMessage());
        body.put("idempotencyKey", ex.idempotencyKey());
        body.put("hint", "retry with exponential backoff, then GET /payments/{id} to resolve state");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "validation_failed", message);
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