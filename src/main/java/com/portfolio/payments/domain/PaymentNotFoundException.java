package com.portfolio.payments.domain;

import java.util.UUID;

/**
 * Lancada quando uma operação requer um Payment que não existe no banco.
 * Mapeada para HTTP 404 no controller.
 */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID id) {
        super("payment not found: " + id);
    }

    public PaymentNotFoundException(String key) {
        super("payment not found with idempotency key: " + key);
    }
}