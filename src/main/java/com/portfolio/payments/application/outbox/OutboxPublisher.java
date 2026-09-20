package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.OutboxEvent;

/**
 * Port: publishes a domain event to an external transport.
 *
 * <p>Implementations: {@link StdoutPublisher} (dev), a Kafka publisher,
 * or a Redpanda publisher. The {@link com.portfolio.payments.application.outbox.OutboxRelay}
 * does not know which one it talks to — only that it returns success /
 * failure.</p>
 */
public interface OutboxPublisher {
    PublishResult publish(OutboxEvent event);

    enum PublishResult { SUCCESS, RETRYABLE_FAILURE, GIVE_UP }
}