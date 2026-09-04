package com.portfolio.payments.domain;

/**
 * Estados possíveis de um Payment no ciclo de vida.
 *
 * <p>Transições permitidas (state machine estrita):
 * <pre>
 *   PENDING ──▶ AUTHORIZED ──▶ CAPTURED ──▶ SETTLED
 *      │             │
 *      │             └──▶ FAILED
 *      └──▶ FAILED
 *      └──▶ CANCELLED
 * </pre>
 * Qualquer outra transição lança {@link IllegalStateException}.</p>
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING    -> next == AUTHORIZED || next == FAILED || next == CANCELLED;
            case AUTHORIZED -> next == CAPTURED || next == FAILED;
            case CAPTURED   -> next == SETTLED || next == FAILED;
            case SETTLED, FAILED, CANCELLED -> false;
        };
    }
}