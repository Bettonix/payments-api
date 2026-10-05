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

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCreated() {
        recordCreated("UNKNOWN");
    }

    public void recordCreated(String currency) {
        Counter.builder("payments.created")
            .description("payments successfully created by currency")
            .tag("currency", currency != null && !currency.isBlank() ? currency : "UNKNOWN")
            .register(registry)
            .increment();
    }

    public void recordReplayed() {
        Counter.builder("payments.replayed")
            .description("idempotency replays served from cache/db")
            .register(registry)
            .increment();
    }

    public void recordTransition() {
        recordTransition(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED);
    }

    public void recordTransition(PaymentStatus from, PaymentStatus to) {
        Counter.builder("payments.transitioned")
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