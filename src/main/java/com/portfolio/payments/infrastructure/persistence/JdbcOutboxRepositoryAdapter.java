package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Adapter de persistência para {@link OutboxRepository} usando {@link JdbcClient}.
 *
 * <p>Executa claim com lease e ordem estrita por agregado (head-of-line)
 * usando {@code FOR UPDATE SKIP LOCKED}, e opera na mesma transação JDBC/JPA.</p>
 */
@Component
@Primary
public class JdbcOutboxRepositoryAdapter implements OutboxRepository {

    private final JdbcClient jdbc;
    private final OutboxRowMapper mapper = new OutboxRowMapper();

    public JdbcOutboxRepositoryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public OutboxEvent save(OutboxEvent event) {
        String checkSql = "SELECT count(*) FROM outbox_events WHERE id = :id";
        Long count = jdbc.sql(checkSql)
            .param("id", event.id())
            .query(Long.class)
            .single();

        if (count > 0) {
            String updateSql = """
                UPDATE outbox_events
                SET status = :status,
                    published_at = :publishedAt,
                    attempt_count = :attemptCount,
                    last_error = :lastError,
                    next_attempt_at = :nextAttemptAt,
                    locked_until = :lockedUntil
                WHERE id = :id
                """;
            jdbc.sql(updateSql)
                .param("status", event.status().name())
                .param("publishedAt", toTimestamp(event.publishedAt()))
                .param("attemptCount", event.attemptCount())
                .param("lastError", event.lastError())
                .param("nextAttemptAt", toTimestamp(event.nextAttemptAt()))
                .param("lockedUntil", toTimestamp(event.lockedUntil()))
                .param("id", event.id())
                .update();
        } else {
            String insertSql = """
                INSERT INTO outbox_events
                  (id, merchant_id, aggregate_type, aggregate_id, event_type, payload,
                   created_at, status, published_at, attempt_count, last_error, next_attempt_at, locked_until)
                VALUES
                  (:id, :merchantId, :aggregateType, :aggregateId, :eventType, cast(:payload as jsonb),
                   :createdAt, :status, :publishedAt, :attemptCount, :lastError, :nextAttemptAt, :lockedUntil)
                """;
            jdbc.sql(insertSql)
                .param("id", event.id())
                .param("merchantId", event.merchantId())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .param("createdAt", toTimestamp(event.createdAt()))
                .param("status", event.status().name())
                .param("publishedAt", toTimestamp(event.publishedAt()))
                .param("attemptCount", event.attemptCount())
                .param("lastError", event.lastError())
                .param("nextAttemptAt", toTimestamp(event.nextAttemptAt()))
                .param("lockedUntil", toTimestamp(event.lockedUntil()))
                .update();
        }
        return event;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEvent> fetchPendingBatch(int limit) {
        String sql = """
            SELECT id, merchant_id, aggregate_type, aggregate_id, event_type,
                   payload::text as payload, created_at, status, published_at,
                   attempt_count, last_error, next_attempt_at, locked_until
            FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            """;
        return jdbc.sql(sql)
            .param("limit", limit)
            .query(mapper)
            .list();
    }

    @Override
    @Transactional
    public List<OutboxEvent> claimBatch(int limit, Duration lease) {
        long leaseSeconds = Math.max(1, lease.toSeconds());
        String sql = """
            UPDATE outbox_events o
            SET locked_until = now() + (:leaseSeconds * interval '1 second')
            WHERE o.id IN (
              SELECT e.id FROM outbox_events e
              WHERE e.status = 'PENDING'
                AND e.next_attempt_at <= now()
                AND (e.locked_until IS NULL OR e.locked_until < now())
                AND NOT EXISTS (
                  SELECT 1 FROM outbox_events p
                  WHERE p.aggregate_id = e.aggregate_id
                    AND p.status = 'PENDING'
                    AND p.created_at < e.created_at
                )
              ORDER BY e.created_at ASC
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            )
            RETURNING o.id, o.merchant_id, o.aggregate_type, o.aggregate_id, o.event_type,
                      o.payload::text as payload, o.created_at, o.status, o.published_at,
                      o.attempt_count, o.last_error, o.next_attempt_at, o.locked_until;
            """;
        return jdbc.sql(sql)
            .param("leaseSeconds", leaseSeconds)
            .param("limit", limit)
            .query(mapper)
            .list();
    }

    @Override
    @Transactional
    public void markPublished(Collection<UUID> ids, Instant at) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String sql = """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = :at,
                locked_until = NULL,
                last_error = NULL
            WHERE id IN (:ids)
            """;
        jdbc.sql(sql)
            .param("at", toTimestamp(at))
            .param("ids", ids)
            .update();
    }

    @Override
    @Transactional
    public void scheduleRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
        String sql = """
            UPDATE outbox_events
            SET attempt_count = :attempts,
                next_attempt_at = :nextAttemptAt,
                last_error = :error,
                locked_until = NULL
            WHERE id = :id
            """;
        jdbc.sql(sql)
            .param("attempts", attempts)
            .param("nextAttemptAt", toTimestamp(nextAttemptAt))
            .param("error", error)
            .param("id", id)
            .update();
    }

    @Override
    @Transactional
    public void markFailed(UUID id, int attempts, String error) {
        String sql = """
            UPDATE outbox_events
            SET status = 'FAILED',
                attempt_count = :attempts,
                last_error = :error,
                locked_until = NULL
            WHERE id = :id
            """;
        jdbc.sql(sql)
            .param("attempts", attempts)
            .param("error", error)
            .param("id", id)
            .update();
    }

    @Override
    @Transactional(readOnly = true)
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
            .query(Long.class)
            .single();
    }

    @Override
    @Transactional(readOnly = true)
    public long countFailed() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'FAILED'")
            .query(Long.class)
            .single();
    }

    @Override
    @Transactional
    public int deletePublishedBefore(Instant cutoff, int limit) {
        String sql = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE status = 'PUBLISHED' AND published_at < :cutoff
                LIMIT :limit
            )
            """;
        return jdbc.sql(sql)
            .param("cutoff", toTimestamp(cutoff))
            .param("limit", limit)
            .update();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static class OutboxRowMapper implements RowMapper<OutboxEvent> {
        @Override
        public OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = rs.getObject("id", UUID.class);
            String merchantId = rs.getString("merchant_id");
            String aggregateType = rs.getString("aggregate_type");
            UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
            String eventType = rs.getString("event_type");
            String payload = rs.getString("payload");
            Instant createdAt = toInstant(rs.getTimestamp("created_at"));
            OutboxEvent.Status status = OutboxEvent.Status.valueOf(rs.getString("status"));
            Instant publishedAt = toInstant(rs.getTimestamp("published_at"));
            int attemptCount = rs.getInt("attempt_count");
            String lastError = rs.getString("last_error");
            Instant nextAttemptAt = toInstant(rs.getTimestamp("next_attempt_at"));
            Instant lockedUntil = toInstant(rs.getTimestamp("locked_until"));

            return OutboxEvent.rehydrate(id, merchantId, aggregateType, aggregateId, eventType, payload,
                createdAt, status, publishedAt, attemptCount, lastError, nextAttemptAt, lockedUntil);
        }

        private Instant toInstant(Timestamp ts) {
            return ts != null ? ts.toInstant() : null;
        }
    }
}
