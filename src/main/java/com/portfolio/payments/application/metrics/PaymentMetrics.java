package com.portfolio.payments.application.metrics;

import com.portfolio.payments.domain.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Custom business metrics for payments.
 *
 * <p>Métricas de negócio para criação, replays de idempotência,
 * transições de estado com tags, monitoramento de backlog e
 * timers detalhados de latência por fase do ciclo de vida.</p>
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final Timer ingressTimer;
    private final Timer queueTransitTimer;
    private final Timer dbPersistenceTimer;
    private final Timer e2eTotalTimer;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingressTimer = Timer.builder("payments.latency.ingress")
            .description("Latência da ingestão síncrona na borda até o HTTP 202 Accepted")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.queueTransitTimer = Timer.builder("payments.latency.queue.transit")
            .description("Tempo de espera e trânsito da mensagem na fila Kafka payments.ingress")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.dbPersistenceTimer = Timer.builder("payments.latency.db.persistence")
            .description("Tempo gasto na transação JDBC do PostgreSQL + Outbox")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.e2eTotalTimer = Timer.builder("payments.latency.e2e.total")
            .description("Tempo total ponta a ponta desde o recebimento até a persistência concluída")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);
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

    public void recordIngressLatency(long durationNanos) {
        ingressTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordQueueTransitLatency(long durationNanos) {
        queueTransitTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordDbPersistenceLatency(long durationNanos) {
        dbPersistenceTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordE2eLatency(long durationNanos) {
        e2eTotalTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public Gauge registerGauge(String name, String description, Supplier<Number> supplier) {
        return Gauge.builder(name, supplier)
            .description(description)
            .register(registry);
    }
}