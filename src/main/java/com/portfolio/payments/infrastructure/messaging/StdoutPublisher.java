package com.portfolio.payments.infrastructure.messaging;

import com.portfolio.payments.application.outbox.OutboxPublisher;
import com.portfolio.payments.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev-friendly publisher that logs the event to stdout.
 *
 * <p>ADR-0001: Adaptador de infraestrutura implementando a porta OutboxPublisher.</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher", havingValue = "stdout", matchIfMissing = true)
public class StdoutPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(StdoutPublisher.class);

    @Override
    public PublishResult publish(OutboxEvent event) {
        log.info("OUTBOX-EVENT id={} type={} aggregate={} merchant={} payload={}",
            event.id(), event.eventType(), event.aggregateId(), event.merchantId(), event.payload());
        return PublishResult.SUCCESS;
    }
}
