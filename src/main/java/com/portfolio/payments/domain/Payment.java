package com.portfolio.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root do domínio de pagamentos.
 *
 * <p>Representa a intenção de movimentar {@link Money} de payer → payee.
 * Mutações de estado passam por métodos que validam a transição contra
 * {@link PaymentStatus#canTransitionTo} — encapsula invariantes.</p>
 *
 * <p>Importante: o ID externo (cabecalho {@code Idempotency-Key}) é
 * armazenado em {@link #idempotencyKey} e indexado unique no banco —
 * garante que a mesma chave não processa duas vezes.</p>
 */
public class Payment {

    private final UUID id;
    private final String idempotencyKey;
    private final UUID payerId;
    private final UUID payeeId;
    private final Money amount;
    private PaymentStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private String failureReason;

    private Payment(UUID id, String idempotencyKey, UUID payerId, UUID payeeId,
                    Money amount, PaymentStatus status, Instant createdAt,
                    Instant updatedAt, String failureReason) {
        this.id = Objects.requireNonNull(id);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.payerId = Objects.requireNonNull(payerId);
        this.payeeId = Objects.requireNonNull(payeeId);
        this.amount = Objects.requireNonNull(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive");
        }
        if (payerId.equals(payeeId)) {
            throw new IllegalArgumentException("payer and payee must differ");
        }
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.failureReason = failureReason;
    }

    public static Payment create(String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        Instant now = Instant.now();
        return new Payment(UUID.randomUUID(), idempotencyKey, payerId, payeeId,
                          amount, PaymentStatus.PENDING, now, now, null);
    }

    /** Rehydrate de persistência. */
    public static Payment rehydrate(UUID id, String idempotencyKey, UUID payerId, UUID payeeId,
                                    Money amount, PaymentStatus status, Instant createdAt,
                                    Instant updatedAt, String failureReason) {
        return new Payment(id, idempotencyKey, payerId, payeeId, amount, status,
                          createdAt, updatedAt, failureReason);
    }

    public void authorize() {
        transitionTo(PaymentStatus.AUTHORIZED, null);
    }

    public void capture() {
        transitionTo(PaymentStatus.CAPTURED, null);
    }

    public void settle() {
        transitionTo(PaymentStatus.SETTLED, null);
    }

    public void fail(String reason) {
        transitionTo(PaymentStatus.FAILED, Objects.requireNonNull(reason));
    }

    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED, null);
    }

    private void transitionTo(PaymentStatus next, String reason) {
        if (!this.status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "invalid transition: " + this.status + " -> " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
        this.failureReason = reason;
    }

    public UUID id() { return id; }
    public String idempotencyKey() { return idempotencyKey; }
    public UUID payerId() { return payerId; }
    public UUID payeeId() { return payeeId; }
    public Money amount() { return amount; }
    public PaymentStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public String failureReason() { return failureReason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment p)) return false;
        return id.equals(p.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}