package com.portfolio.payments.application.port;

import com.portfolio.payments.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Comando imutável que encapsula a intenção de pagamento para ingestão assíncrona.
 */
public record PaymentIngressCommand(
    UUID paymentId,
    String merchantId,
    String idempotencyKey,
    String fingerprint,
    UUID payerId,
    UUID payeeId,
    Money amount,
    Instant requestedAt
) {
    public PaymentIngressCommand {
        Objects.requireNonNull(paymentId, "paymentId required");
        Objects.requireNonNull(merchantId, "merchantId required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey required");
        Objects.requireNonNull(payerId, "payerId required");
        Objects.requireNonNull(payeeId, "payeeId required");
        Objects.requireNonNull(amount, "amount required");
        Objects.requireNonNull(requestedAt, "requestedAt required");
    }
}
