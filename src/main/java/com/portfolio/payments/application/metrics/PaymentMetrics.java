package com.portfolio.payments.application.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics for payments.
 *
 * <p>Why custom metrics on top of the built-in Spring/Hikari/JVM ones?
 * Because business-relevant counters (created vs replayed, transitions
 * by target status, outbox backlog) are what you actually alert on in
 * production — they're not in the defaults.</p>
 *
 * <p>Exposed via /actuator/prometheus and consumed by Grafana / Datadog
 * / whatever scrape target.</p>
 */
@Component
public class PaymentMetrics {

    private final Counter createdCounter;
    private final Counter replayedCounter;
    private final Counter transitionCounter;
    private final MeterRegistry registry;

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

    public void recordReplayed() {
        replayedCounter.increment();
    }

    public void recordTransition() {
        transitionCounter.increment();
    }

    /** Registers a gauge backed by a supplier (e.g. outbox backlog). */
    public Gauge registerGauge(String name, String description, java.util.function.Supplier<Number> supplier) {
        return Gauge.builder(name, supplier)
            .description(description)
            .register(registry);
    }
}