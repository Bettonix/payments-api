package com.portfolio.payments.application.metrics;

import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Background outbox gauge: shows how many pending events are stuck.
 *
 * <p>Why this matters: the relay drains every 5s, but if the publisher
 * is down or slow, the queue grows. Alert when this gauge stops going
 * back to zero — that means the relay isn't keeping up.</p>
 */
@Component
public class OutboxBacklogGauge {

    private final MeterRegistry registry;
    private final OutboxRepository repository;

    public OutboxBacklogGauge(MeterRegistry registry, OutboxRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @PostConstruct
    public void register() {
        Gauge.builder("payments_outbox_pending", this, g -> g.repository.fetchPendingBatch(1000).size())
            .description("events waiting in the outbox to be published")
            .register(registry);
    }
}