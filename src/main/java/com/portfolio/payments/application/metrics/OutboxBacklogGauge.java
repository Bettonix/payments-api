package com.portfolio.payments.application.metrics;

import com.portfolio.payments.domain.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Background outbox gauge: shows how many pending/failed events exist.
 *
 * <p>C6 Fix: Usa {@code countPending()} diretamente em vez de {@code fetchPendingBatch(1000)}
 * que disparava locks pessimistas e carregava centenas de entidades desnecessariamente.</p>
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
        Gauge.builder("payments_outbox_pending", this, g -> g.repository.countPending())
            .description("events waiting in the outbox to be published")
            .register(registry);

        Gauge.builder("payments_outbox_failed", this, g -> g.repository.countFailed())
            .description("events permanently failed in the outbox")
            .register(registry);
    }
}