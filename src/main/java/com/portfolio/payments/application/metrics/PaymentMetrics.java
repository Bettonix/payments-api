package com.portfolio.payments.application.metrics;

import com.portfolio.payments.domain.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Custom business metrics for payments.
 *
 * <p>Métricas de negócio para criação, replays de idempotência,
 * transições de estado com tags e monitoramento de backlog.</p>
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final Counter createdCounter;
    private final Counter replayedCounter;
    private final Counter transitionCounter;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.createdCounter = Counter.builder("payments_created_total")
            .description("payments successfully created")
            .register(registry);
        this.replayedCounter = Counter.builder("payments_replayed_total")
            .description("idempotency replays served from cache/db")
            .register(registry);
        this.transitionCounter = Counter.builder("payments_transitioned_total")
            .description("state transitions applied to payments")
            .register(registry);
    }

    public void recordCreated() {
        createdCounter.increment();
    }

    public void recordCreated(String currency) {
        recordCreated();
        Counter.builder("payments_created_total")
            .description("payments successfully created by currency")
            .tag("currency", currency != null ? currency : "UNKNOWN")
            .register(registry)
            .increment();
    }

    public void recordReplayed() {
        replayedCounter.increment();
    }

    public void recordTransition() {
        transitionCounter.increment();
    }

    public void recordTransition(PaymentStatus from, PaymentStatus to) {
        recordTransition();
        Counter.builder("payments_transitioned_total")
            .description("state transitions applied with tags")
            .tag("from", from != null ? from.name() : "NONE")
            .tag("to", to != null ? to.name() : "NONE")
            .register(registry)
            .increment();
    }

    public Gauge registerGauge(String name, String description, Supplier<Number> supplier) {
        return Gauge.builder(name, supplier)
            .description(description)
            .register(registry);
    }
}