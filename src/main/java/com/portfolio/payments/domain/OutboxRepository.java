package com.portfolio.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Port for the transactional outbox.
 *
 * <p>The relay uses {@link #claimBatch} to grab a slice of pending
 * events with a lease and head-of-line aggregate ordering;
 * {@link #save} writes the initial event during domain use cases.</p>
 */
public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
    List<OutboxEvent> fetchPendingBatch(int limit);
    List<OutboxEvent> claimBatch(int limit, Duration lease);
    void markPublished(Collection<UUID> ids, Instant at);
    void scheduleRetry(UUID id, int attempts, Instant nextAttemptAt, String error);
    void markFailed(UUID id, int attempts, String error);
    long countPending();
    long countFailed();
    int deletePublishedBefore(Instant cutoff, int limit);
}