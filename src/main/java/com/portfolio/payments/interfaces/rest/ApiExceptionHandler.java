package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.IdempotencyPayloadMismatchException;
import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PreconditionFailedException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mapeia exceções em respostas RFC 9457 (ProblemDetail com Content-Type application/problem+json).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        log.debug("payment not found: {}", ex.getMessage());
        ProblemDetail problem = buildProblem(
            HttpStatus.NOT_FOUND,
            "urn:problem-type:payment-not-found",
            "Payment Not Found",
            ex.getMessage(),
            request
        );
        return response(problem, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyKeyConflictException ex, HttpServletRequest request) {
        log.info("idempotency conflict: key={}", ex.idempotencyKey());
        ProblemDetail problem = buildProblem(
            HttpStatus.CONFLICT,
            "urn:problem-type:idempotency-request-in-progress",
            "Idempotency Request In Progress",
            ex.getMessage(),
            request
        );
        problem.setProperty("idempotencyKey", ex.idempotencyKey());
        problem.setProperty("hint", "A request with this Idempotency-Key is currently in progress or recently executed. Retry shortly.");

        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ResponseEntity<ProblemDetail> handlePayloadMismatch(IdempotencyPayloadMismatchException ex, HttpServletRequest request) {
        log.warn("idempotency payload mismatch: {}", ex.getMessage());
        ProblemDetail problem = buildProblem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "urn:problem-type:idempotency-key-reused",
            "Idempotency Key Reused",
            ex.getMessage(),
            request
        );
        problem.setProperty("idempotencyKey", ex.idempotencyKey());
        return response(problem, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(InvalidPaymentTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTransition(InvalidPaymentTransitionException ex, HttpServletRequest request) {
        log.debug("invalid payment transition: {}", ex.getMessage());
        ProblemDetail problem = buildProblem(
            HttpStatus.CONFLICT,
            "urn:problem-type:invalid-state-transition",
            "Invalid State Transition",
            ex.getMessage(),
            request
        );
        problem.setProperty("paymentId", ex.paymentId());
        problem.setProperty("from", ex.from().name());
        problem.setProperty("to", ex.to().name());
        return response(problem, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ProblemDetail> handlePreconditionFailed(PreconditionFailedException ex, HttpServletRequest request) {
        log.debug("precondition failed: {}", ex.getMessage());
        ProblemDetail problem = buildProblem(
            HttpStatus.PRECONDITION_FAILED,
            "urn:problem-type:precondition-failed",
            "Precondition Failed",
            ex.getMessage(),
            request
        );
        problem.setProperty("expectedETag", ex.expectedETag());
        problem.setProperty("currentETag", ex.currentETag());
        return response(problem, HttpStatus.PRECONDITION_FAILED);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("optimistic lock failure: {}", ex.getMessage());
        ProblemDetail problem = buildProblem(
            HttpStatus.CONFLICT,
            "urn:problem-type:concurrent-modification",
            "Concurrent Modification",
            "The resource was modified concurrently by another request. Please reload and retry.",
            request
        );
        return response(problem, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field", fe.getField(),
                "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"
            ))
            .toList();

        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "urn:problem-type:validation-error",
            "Validation Failed",
            "One or more request parameters failed validation.",
            request
        );
        problem.setProperty("errors", fieldErrors);
        return response(problem, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<String> errors = ex.getAllValidationResults().stream()
            .flatMap(vr -> vr.getResolvableErrors().stream())
            .map(err -> err.getDefaultMessage() != null ? err.getDefaultMessage() : "invalid")
            .toList();

        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "urn:problem-type:validation-error",
            "Validation Failed",
            "Request parameters failed validation: " + String.join("; ", errors),
            request
        );
        problem.setProperty("errors", errors);
        return response(problem, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        String type = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
            ? "urn:problem-type:idempotency-key-missing"
            : "urn:problem-type:missing-header";

        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            type,
            "Missing Required Header",
            "Required header '" + ex.getHeaderName() + "' is missing",
            request
        );
        return response(problem, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex, HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
            HttpStatus.BAD_REQUEST,
            "urn:problem-type:bad-request",
            "Bad Request",
            ex.getMessage() != null ? ex.getMessage() : "Malformed request",
            request
        );
        return response(problem, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({
        org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ProblemDetail> handleNotFoundResource(Exception ex, HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
            HttpStatus.NOT_FOUND,
            "urn:problem-type:resource-not-found",
            "Resource Not Found",
            ex.getMessage() != null ? ex.getMessage() : "Resource not found",
            request
        );
        return response(problem, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(org.springframework.web.ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleErrorResponse(org.springframework.web.ErrorResponseException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ProblemDetail problem = buildProblem(
            status,
            "urn:problem-type:" + status.name().toLowerCase().replace('_', '-'),
            status.getReasonPhrase(),
            ex.getBody().getDetail() != null ? ex.getBody().getDetail() : ex.getMessage(),
            request
        );
        return response(problem, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAny(Exception ex, HttpServletRequest request) {
        log.error("unhandled server exception", ex);
        ProblemDetail problem = buildProblem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "urn:problem-type:internal-error",
            "Internal Server Error",
            "An unexpected error occurred. Please contact support with the traceId.",
            request
        );
        return response(problem, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ProblemDetail buildProblem(HttpStatus status, String type, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }

        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        problem.setProperty("traceId", traceId);
        problem.setProperty("timestamp", Instant.now().toString());

        return problem;
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem, HttpStatus status) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }
}