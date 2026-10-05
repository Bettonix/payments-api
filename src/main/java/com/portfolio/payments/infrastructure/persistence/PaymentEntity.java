package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * JPA entity — representação tabular do agregado Payment.
 *
 * <p>C1 Fix: Implementa {@link Persistable} com {@code isNew} flag para garantir
 * que o Spring Data JPA invoque {@code persist()} em vez de {@code merge()},
 * e troca {@code version} primitivo por wrapper {@link Long}.</p>
 */
@Entity
@Table(name = "payments")
public class PaymentEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

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
    @Column(name = "version")
    private Long version;

    @Transient
    private boolean isNew = true;

    protected PaymentEntity() { /* JPA */ }

    public static PaymentEntity fromDomain(Payment p) {
        PaymentEntity e = new PaymentEntity();
        e.id = p.id();
        e.merchantId = p.merchantId();
        e.idempotencyKey = p.idempotencyKey();
        e.requestFingerprint = p.requestFingerprint();
        e.payerId = p.payerId();
        e.payeeId = p.payeeId();
        e.amount = p.amount().amount();
        e.currency = p.amount().currency().getCurrencyCode();
        e.status = p.status();
        e.failureReason = p.failureReason();
        e.createdAt = p.createdAt();
        e.updatedAt = p.updatedAt();
        e.version = p.version();
        e.isNew = (p.version() == null);
        return e;
    }

    public void updateFrom(Payment p) {
        this.status = p.status();
        this.failureReason = p.failureReason();
        this.updatedAt = p.updatedAt();
    }

    public Payment toDomain() {
        return Payment.rehydrate(
            id, merchantId, idempotencyKey, requestFingerprint, payerId, payeeId,
            new Money(amount, Currency.getInstance(currency)),
            status, createdAt, updatedAt, failureReason, version);
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getPayerId() { return payerId; }
    public UUID getPayeeId() { return payeeId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}