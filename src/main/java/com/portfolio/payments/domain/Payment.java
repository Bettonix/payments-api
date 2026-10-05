package com.portfolio.payments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root do domínio de pagamentos.
 *
 * <p>Representa a intenção de movimentar {@link Money} de payer → payee.
 * Mutações de estado passam por métodos que validam a transição contra
 * {@link PaymentStatus#canTransitionTo} — encapsula invariantes.
 *
 * <p>Emite domain events através de {@link #pullEvents()} que são gravados
 * transactionalmente no outbox.</p>
 */
public class Payment {

    public static final String DEFAULT_MERCHANT_ID = "legacy";

    private final UUID id;
    private final String merchantId;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final UUID payerId;
    private final UUID payeeId;
    private final Money amount;
    private PaymentStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private String failureReason;
    private Long version;

    private final List<PaymentEvent> domainEvents = new ArrayList<>();

    private Payment(UUID id, String merchantId, String idempotencyKey, String requestFingerprint,
                    UUID payerId, UUID payeeId, Money amount, PaymentStatus status,
                    Instant createdAt, Instant updatedAt, String failureReason, Long version) {
        this.id = Objects.requireNonNull(id, "id required");
        this.merchantId = merchantId != null && !merchantId.isBlank() ? merchantId : DEFAULT_MERCHANT_ID;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey required");
        this.requestFingerprint = requestFingerprint;
        this.payerId = Objects.requireNonNull(payerId, "payerId required");
        this.payeeId = Objects.requireNonNull(payeeId, "payeeId required");
        this.amount = Objects.requireNonNull(amount, "amount required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive");
        }
        if (payerId.equals(payeeId)) {
            throw new IllegalArgumentException("payer and payee must differ");
        }
        this.status = Objects.requireNonNull(status, "status required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt required");
        this.failureReason = failureReason;
        this.version = version;
    }

    public static Payment create(String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        return create(DEFAULT_MERCHANT_ID, idempotencyKey, null, payerId, payeeId, amount);
    }

    public static Payment create(String merchantId, String idempotencyKey, String requestFingerprint,
                                 UUID payerId, UUID payeeId, Money amount) {
        return createWithId(UUID.randomUUID(), merchantId, idempotencyKey, requestFingerprint, payerId, payeeId, amount);
    }

    public static Payment createWithId(UUID id, String merchantId, String idempotencyKey, String requestFingerprint,
                                       UUID payerId, UUID payeeId, Money amount) {
        Instant now = Instant.now();
        Payment payment = new Payment(id, merchantId, idempotencyKey, requestFingerprint,
            payerId, payeeId, amount, PaymentStatus.PENDING, now, now, null, null);
        payment.recordEvent(new PaymentEvent.PaymentCreated(
            payment.id, payment.merchantId, payment.idempotencyKey,
            payment.payerId, payment.payeeId, payment.amount, payment.status, now
        ));
        return payment;
    }

    /** Rehydrate de persistência. */
    public static Payment rehydrate(UUID id, String merchantId, String idempotencyKey, String requestFingerprint,
                                    UUID payerId, UUID payeeId, Money amount, PaymentStatus status,
                                    Instant createdAt, Instant updatedAt, String failureReason, Long version) {
        return new Payment(id, merchantId, idempotencyKey, requestFingerprint, payerId, payeeId,
            amount, status, createdAt, updatedAt, failureReason, version);
    }

    public static Payment rehydrate(UUID id, String idempotencyKey, UUID payerId, UUID payeeId,
                                    Money amount, PaymentStatus status, Instant createdAt,
                                    Instant updatedAt, String failureReason) {
        return rehydrate(id, DEFAULT_MERCHANT_ID, idempotencyKey, null, payerId, payeeId,
            amount, status, createdAt, updatedAt, failureReason, null);
    }

    public void authorize() {
        transitionTo(PaymentStatus.AUTHORIZED, null);
        recordEvent(new PaymentEvent.PaymentAuthorized(this.id, this.merchantId, Instant.now()));
    }

    public void capture() {
        transitionTo(PaymentStatus.CAPTURED, null);
        recordEvent(new PaymentEvent.PaymentCaptured(this.id, this.merchantId, Instant.now()));
    }

    public void settle() {
        transitionTo(PaymentStatus.SETTLED, null);
        recordEvent(new PaymentEvent.PaymentSettled(this.id, this.merchantId, Instant.now()));
    }

    public void fail(String reason) {
        transitionTo(PaymentStatus.FAILED, Objects.requireNonNull(reason, "reason required"));
        recordEvent(new PaymentEvent.PaymentFailed(this.id, this.merchantId, reason, Instant.now()));
    }

    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED, null);
        recordEvent(new PaymentEvent.PaymentCancelled(this.id, this.merchantId, Instant.now()));
    }

    private void transitionTo(PaymentStatus next, String reason) {
        if (!this.status.canTransitionTo(next)) {
            throw new InvalidPaymentTransitionException(this.id, this.status, next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
        this.failureReason = reason;
    }

    private void recordEvent(PaymentEvent event) {
        this.domainEvents.add(event);
    }

    /** Retorna e limpa os eventos de domínio acumulados. */
    public List<PaymentEvent> pullEvents() {
        List<PaymentEvent> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return Collections.unmodifiableList(events);
    }

    public UUID id() { return id; }
    public String merchantId() { return merchantId; }
    public String idempotencyKey() { return idempotencyKey; }
    public String requestFingerprint() { return requestFingerprint; }
    public UUID payerId() { return payerId; }
    public UUID payeeId() { return payeeId; }
    public Money amount() { return amount; }
    public PaymentStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public String failureReason() { return failureReason; }
    public Long version() { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment p)) return false;
        return id.equals(p.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}