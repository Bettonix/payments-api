package com.portfolio.payments.domain;

/**
 * Sinaliza conflito quando a constraint UNIQUE em {@code idempotency_key}
 * é violada por uma escrita concorrente — duas requisições com a mesma
 * Idempotency-Key chegando simultaneamente no caminho de INSERT.
 *
 * <p>Esse é o caso que o ADR-0002 documenta como "race do SELECT+INSERT".
 * O fast-path de replay (SELECT antes do INSERT) pega a maioria dos casos;
 * esse sinaliza o corner case em que duas threads passaram o SELECT ao
 * mesmo tempo e colidiram no INSERT.</p>
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("idempotency key already used by a concurrent request: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
