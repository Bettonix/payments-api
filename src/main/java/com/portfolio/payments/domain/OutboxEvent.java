package com.portfolio.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * OutboxEvent — aggregate in the transactional outbox pattern.
 *
 * <p>Represents an event that must be published to a broker, written in
 * the same DB transaction as the domain change. The {@link OutboxRelay}
 * picks pending events and dispatches them.</p>
 *
 * <p>Status lifecycle: PENDING -> PUBLISHED (success) | FAILED (terminal
 * after retries exhausted).</p>
 */
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    private final UUID id;
    private final String aggregateType;
    private final UUID aggregateId;
    private final String eventType;
    private final String payload;
    private final Instant createdAt;
    private Status status;
    private Instant publishedAt;
    private int attemptCount;
    private String lastError;

    private OutboxEvent(UUID id, String aggregateType, UUID aggregateId,
                        String eventType, String payload, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.aggregateType = Objects.requireNonNull(aggregateType);
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.eventType = Objects.requireNonNull(eventType);
        this.payload = Objects.requireNonNull(payload);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = Status.PENDING;
    }

    /** Build a new pending event from a domain aggregate. */
    public static OutboxEvent create(String aggregateType, UUID aggregateId,
                                     String eventType, String payload) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId,
            eventType, payload, Instant.now());
    }

    /** Rehydrate from persistence. */
    public static OutboxEvent rehydrate(UUID id, String aggregateType, UUID aggregateId,
                                        String eventType, String payload,
                                        Instant createdAt, Status status,
                                        Instant publishedAt, int attemptCount, String lastError) {
        OutboxEvent e = new OutboxEvent(id, aggregateType, aggregateId, eventType, payload, createdAt);
        e.status = status;
        e.publishedAt = publishedAt;
        e.attemptCount = attemptCount;
        e.lastError = lastError;
        return e;
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attemptCount++;
        this.lastError = Objects.requireNonNull(error);
    }

    public void markGiveUp(String error) {
        this.status = Status.FAILED;
        this.attemptCount++;
        this.lastError = Objects.requireNonNull(error);
    }

    public UUID id() { return id; }
    public String aggregateType() { return aggregateType; }
    public UUID aggregateId() { return aggregateId; }
    public String eventType() { return eventType; }
    public String payload() { return payload; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public int attemptCount() { return attemptCount; }
    public String lastError() { return lastError; }
}