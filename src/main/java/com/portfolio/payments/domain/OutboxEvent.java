package com.portfolio.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * OutboxEvent — aggregate in the transactional outbox pattern.
 *
 * <p>Represents an event that must be published to a broker, written in
 * the same DB transaction as the domain change. The relay picks pending
 * events and dispatches them.</p>
 *
 * <p>Status lifecycle: PENDING -> PUBLISHED (success) | FAILED (terminal
 * after retries exhausted).</p>
 */
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    private final UUID id;
    private final String merchantId;
    private final String aggregateType;
    private final UUID aggregateId;
    private final String eventType;
    private final String payload;
    private final Instant createdAt;
    private Status status;
    private Instant publishedAt;
    private int attemptCount;
    private String lastError;
    private Instant nextAttemptAt;
    private Instant lockedUntil;

    private OutboxEvent(UUID id, String merchantId, String aggregateType, UUID aggregateId,
                        String eventType, String payload, Instant createdAt, Status status,
                        Instant publishedAt, int attemptCount, String lastError,
                        Instant nextAttemptAt, Instant lockedUntil) {
        this.id = Objects.requireNonNull(id, "id required");
        this.merchantId = merchantId != null ? merchantId : Payment.DEFAULT_MERCHANT_ID;
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType required");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId required");
        this.eventType = Objects.requireNonNull(eventType, "eventType required");
        this.payload = Objects.requireNonNull(payload, "payload required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt required");
        this.status = Objects.requireNonNull(status, "status required");
        this.publishedAt = publishedAt;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt != null ? nextAttemptAt : createdAt;
        this.lockedUntil = lockedUntil;
    }

    /** Build a new pending event from a domain aggregate. */
    public static OutboxEvent create(String merchantId, String aggregateType, UUID aggregateId,
                                     String eventType, String payload) {
        Instant now = Instant.now();
        return new OutboxEvent(UUID.randomUUID(), merchantId, aggregateType, aggregateId,
            eventType, payload, now, Status.PENDING, null, 0, null, now, null);
    }

    public static OutboxEvent create(String aggregateType, UUID aggregateId,
                                     String eventType, String payload) {
        return create(Payment.DEFAULT_MERCHANT_ID, aggregateType, aggregateId, eventType, payload);
    }

    /** Rehydrate from persistence. */
    public static OutboxEvent rehydrate(UUID id, String merchantId, String aggregateType, UUID aggregateId,
                                        String eventType, String payload, Instant createdAt, Status status,
                                        Instant publishedAt, int attemptCount, String lastError,
                                        Instant nextAttemptAt, Instant lockedUntil) {
        return new OutboxEvent(id, merchantId, aggregateType, aggregateId, eventType, payload,
            createdAt, status, publishedAt, attemptCount, lastError, nextAttemptAt, lockedUntil);
    }

    public static OutboxEvent rehydrate(UUID id, String aggregateType, UUID aggregateId,
                                        String eventType, String payload, Instant createdAt, Status status,
                                        Instant publishedAt, int attemptCount, String lastError) {
        return rehydrate(id, Payment.DEFAULT_MERCHANT_ID, aggregateType, aggregateId, eventType, payload,
            createdAt, status, publishedAt, attemptCount, lastError, createdAt, null);
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
        this.lockedUntil = null;
    }

    public void markFailed(String error) {
        this.attemptCount++;
        this.lastError = Objects.requireNonNull(error);
        this.lockedUntil = null;
    }

    public void scheduleRetry(Duration delay, String error) {
        this.attemptCount++;
        this.lastError = Objects.requireNonNull(error);
        this.nextAttemptAt = Instant.now().plus(delay);
        this.lockedUntil = null;
    }

    public void markGiveUp(String error) {
        this.status = Status.FAILED;
        this.attemptCount++;
        this.lastError = Objects.requireNonNull(error);
        this.lockedUntil = null;
    }

    public UUID id() { return id; }
    public String merchantId() { return merchantId; }
    public String aggregateType() { return aggregateType; }
    public UUID aggregateId() { return aggregateId; }
    public String eventType() { return eventType; }
    public String payload() { return payload; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public int attemptCount() { return attemptCount; }
    public String lastError() { return lastError; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public Instant lockedUntil() { return lockedUntil; }
}