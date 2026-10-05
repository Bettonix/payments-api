package com.portfolio.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Eventos de domínio emitidos pelo ciclo de vida do agregado Payment.
 *
 * <p>Sealed interface com eventos tipados para garantir exhaustiveness
 * e evitar serialização manual frágil (C3).</p>
 */
public sealed interface PaymentEvent permits
    PaymentEvent.PaymentCreated,
    PaymentEvent.PaymentAuthorized,
    PaymentEvent.PaymentCaptured,
    PaymentEvent.PaymentSettled,
    PaymentEvent.PaymentFailed,
    PaymentEvent.PaymentCancelled {

    UUID paymentId();
    String merchantId();
    Instant occurredAt();
    String eventType();

    record PaymentCreated(
        UUID paymentId,
        String merchantId,
        String idempotencyKey,
        UUID payerId,
        UUID payeeId,
        Money amount,
        PaymentStatus status,
        Instant occurredAt
    ) implements PaymentEvent {
        @Override
        public String eventType() {
            return "PaymentCreated";
        }
    }

    record PaymentAuthorized(
        UUID paymentId,
        String merchantId,
        Instant occurredAt
    ) implements PaymentEvent {
        @Override
        public String eventType() {
            return "PaymentAuthorized";
        }
    }

    record PaymentCaptured(
        UUID paymentId,
        String merchantId,
        Instant occurredAt
    ) implements PaymentEvent {
        @Override
        public String eventType() {
            return "PaymentCaptured";
        }
    }

    record PaymentSettled(
        UUID paymentId,
        String merchantId,
        Instant occurredAt
    ) implements PaymentEvent {
        @Override
        public String eventType() {
            return "PaymentSettled";
        }
    }

    record PaymentFailed(
        UUID paymentId,
        String merchantId,
        String reason,
        Instant occurredAt
    ) implements PaymentEvent {
        @Override
        public String eventType() {
            return "PaymentFailed";
        }
    }

    record PaymentCancelled(
        UUID paymentId,
        String merchantId,
        Instant occurredAt
    ) implements PaymentEvent {
        @Override
        public String eventType() {
            return "PaymentCancelled";
        }
    }
}
