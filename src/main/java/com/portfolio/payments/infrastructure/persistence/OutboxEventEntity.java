package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.OutboxEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the outbox table.
 *
 * <p>Payload is JSONB in postgres so we can index/search events later if
 * needed. The Java side just stores a String — conversion to JSON happens
 * at the application boundary if needed.</p>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxEvent.Status status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OutboxEventEntity() {}

    public static OutboxEventEntity fromDomain(OutboxEvent e) {
        OutboxEventEntity row = new OutboxEventEntity();
        row.id = e.id();
        row.aggregateType = e.aggregateType();
        row.aggregateId = e.aggregateId();
        row.eventType = e.eventType();
        row.payload = e.payload();
        row.createdAt = e.createdAt();
        row.status = e.status();
        row.publishedAt = e.publishedAt();
        row.attemptCount = e.attemptCount();
        row.lastError = e.lastError();
        return row;
    }

    public OutboxEvent toDomain() {
        return OutboxEvent.rehydrate(id, aggregateType, aggregateId, eventType, payload,
            createdAt, status, publishedAt, attemptCount, lastError);
    }

    /** In-place update of mutable fields so JPA's @Version increments. */
    public void updateFrom(OutboxEvent e) {
        this.status = e.status();
        this.publishedAt = e.publishedAt();
        this.attemptCount = e.attemptCount();
        this.lastError = e.lastError();
    }
}