package com.portfolio.payments.domain;

import java.util.UUID;

/**
 * Lancada quando se tenta mover um Payment para um estado inválido.
 * Mapeada para HTTP 409 (Conflict) no controller.
 *
 * <p>Exemplo: tentar capturar um Payment em PENDING (precisa passar por
 * AUTHORIZED antes).</p>
 */
public class InvalidPaymentTransitionException extends RuntimeException {

    private final UUID paymentId;
    private final PaymentStatus from;
    private final PaymentStatus to;

    public InvalidPaymentTransitionException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super("invalid transition for payment " + paymentId + ": " + from + " -> " + to);
        this.paymentId = paymentId;
        this.from = from;
        this.to = to;
    }

    public UUID paymentId() { return paymentId; }
    public PaymentStatus from() { return from; }
    public PaymentStatus to() { return to; }
}