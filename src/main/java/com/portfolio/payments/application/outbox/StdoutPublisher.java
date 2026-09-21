package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev-friendly publisher that logs the event to stdout. Useful before
 * wiring a real broker (Kafka/Redpanda) — the relay still exercises the
 * full outbox flow, just doesn't talk to anything outside the process.
 *
 * <p>Ativado por padrão (default). Desativado quando
 * {@code app.outbox.publisher=kafka} — nesse caso o {@link KafkaPublisher}
 * assume. Configurável via SPRING_APPLICATION_JSON ou application-<profile>.yml.</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher", havingValue = "stdout", matchIfMissing = true)
public class StdoutPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(StdoutPublisher.class);

    @Override
    public PublishResult publish(OutboxEvent event) {
        log.info("OUTBOX-EVENT id={} type={} aggregate={} payload={}",
            event.id(), event.eventType(), event.aggregateId(), event.payload());
        return PublishResult.SUCCESS;
    }
}