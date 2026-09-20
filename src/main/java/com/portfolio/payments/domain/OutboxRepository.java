package com.portfolio.payments.domain;

import java.util.List;
import java.util.UUID;

/**
 * Port for the transactional outbox.
 *
 * <p>The relay uses {@link #fetchPendingBatch} to grab a slice of pending
 * events ordered oldest-first; {@link #save} writes either the original
 * insert (used by domain use cases) or the updated event after the relay
 * marks it published / failed.</p>
 */
public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
    List<OutboxEvent> fetchPendingBatch(int limit);
}