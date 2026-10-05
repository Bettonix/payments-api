package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.OutboxEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port: publishes a domain event to an external transport.
 *
 * <p>Implementations: StdoutPublisher (dev), KafkaOutboxPublisher (prod).
 * The {@link OutboxRelay} does not know which one it talks to — only that
 * it returns success / failure / deferred.</p>
 */
public interface OutboxPublisher {
    PublishResult publish(OutboxEvent event);

    default Map<UUID, PublishResult> publishBatch(List<OutboxEvent> events) {
        Map<UUID, PublishResult> results = new HashMap<>();
        for (OutboxEvent event : events) {
            results.put(event.id(), publish(event));
        }
        return results;
    }

    enum PublishResult { SUCCESS, RETRYABLE_FAILURE, GIVE_UP, DEFERRED }
}