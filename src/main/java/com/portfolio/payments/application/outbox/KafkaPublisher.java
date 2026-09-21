package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Publisher que envia eventos pro Kafka. Ativado quando a property
 * {@code app.outbox.publisher=kafka} estiver setada (default: stdout).
 *
 * <p>Implementação de produção — substitui o {@link StdoutPublisher} quando
 * o Kafka estiver disponível. O circuit breaker em {@code OutboxRelay} protege
 * contra indisponibilidade do broker: eventos que falham viram
 * {@code RETRYABLE_FAILURE} e ficam na fila pra próxima tentativa.</p>
 *
 * <p>Mapeamento de partição: usamos o aggregate_id como key, garantindo que
 * eventos do mesmo Payment caem na mesma partição (preservando ordem).</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher", havingValue = "kafka")
public class KafkaPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);
    private static final String DEFAULT_TOPIC = "payments.events";

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaPublisher(KafkaTemplate<String, String> kafka) {
        this(kafka, DEFAULT_TOPIC);
    }

    KafkaPublisher(KafkaTemplate<String, String> kafka, String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public PublishResult publish(OutboxEvent event) {
        String key = event.aggregateId().toString();
        try {
            // send() é async; get() bloqueia esperando confirmação do broker
            kafka.send(topic, key, event.payload()).get(5, TimeUnit.SECONDS);
            log.info("OUTBOX-KAFKA id={} type={} key={} topic={}",
                event.id(), event.eventType(), key, topic);
            return PublishResult.SUCCESS;
        } catch (java.util.concurrent.TimeoutException ex) {
            log.warn("kafka publish timeout for event {}: {}", event.id(), ex.getMessage());
            return PublishResult.RETRYABLE_FAILURE;
        } catch (java.util.concurrent.ExecutionException ex) {
            // ExecutionException envolve a causa real
            Throwable cause = ex.getCause();
            log.warn("kafka publish failed for event {}: {}", event.id(), cause.getMessage());
            // 4xx-like → não adianta retentar (mensagem má formada). Por ora tratamos tudo como retryable.
            return PublishResult.RETRYABLE_FAILURE;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return PublishResult.RETRYABLE_FAILURE;
        }
    }
}
