package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * JPA entity — representação tabular do agregado Payment.
 *
 * <p>Decisão: a entity vive em {@code infrastructure}, o domínio nem
 * sabe que JPA existe. Conversão para/domain via métodos estáticos
 * {@link #toDomain} e {@link #fromDomain}.</p>
 *
 * <p>{@code @Version} habilita optimistic locking — duas transações
 * concorrentes que tentam mudar o mesmo payment resultam em
 * OptimisticLockException, evitando double-spend.</p>
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "payer_id", nullable = false)
    private UUID payerId;

    @Column(name = "payee_id", nullable = false)
    private UUID payeeId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentEntity() { /* JPA */ }

    public static PaymentEntity fromDomain(Payment p) {
        PaymentEntity e = new PaymentEntity();
        e.id = p.id();
        e.idempotencyKey = p.idempotencyKey();
        e.payerId = p.payerId();
        e.payeeId = p.payeeId();
        e.amount = p.amount().amount();
        e.currency = p.amount().currency().getCurrencyCode();
        e.status = p.status();
        e.failureReason = p.failureReason();
        e.createdAt = p.createdAt();
        e.updatedAt = p.updatedAt();
        return e;
    }

    /**
     * Updates the mutable fields from the domain aggregate in-place.
     * Identity (id, idempotency_key, payer_id, payee_id, amount, currency,
     * created_at) is preserved — only status, failure_reason, and updated_at
     * are mutated. This keeps the JPA {@code @Version} counter coherent.
     */
    public void updateFrom(Payment p) {
        this.status = p.status();
        this.failureReason = p.failureReason();
        this.updatedAt = p.updatedAt();
    }

    public Payment toDomain() {
        return Payment.rehydrate(
            id, idempotencyKey, payerId, payeeId,
            new Money(amount, Currency.getInstance(currency)),
            status, createdAt, updatedAt, failureReason);
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getPayerId() { return payerId; }
    public UUID getPayeeId() { return payeeId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}