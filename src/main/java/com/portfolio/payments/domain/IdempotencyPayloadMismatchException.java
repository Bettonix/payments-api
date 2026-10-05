package com.portfolio.payments.domain;

/**
 * Lançada quando uma requisição reutiliza uma {@code Idempotency-Key} existente
 * mas com um payload diferente (RFC 9457 / IETF Idempotency-Key draft).
 * Deve resultar em HTTP 422 Unprocessable Entity.
 */
public class IdempotencyPayloadMismatchException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyPayloadMismatchException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was previously used with a different request payload");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
