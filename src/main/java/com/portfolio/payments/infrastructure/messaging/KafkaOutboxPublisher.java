package com.portfolio.payments.infrastructure.messaging;

import com.portfolio.payments.application.outbox.OutboxPublisher;
import com.portfolio.payments.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publisher que envia eventos para o Kafka com CloudEvents headers (binary mode)
 * e publicação assíncrona por lote.
 *
 * <p>ADR-0001: Componente de infraestrutura implementando a porta OutboxPublisher.</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher", havingValue = "kafka")
public class KafkaOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private static final String DEFAULT_TOPIC = "payments.events";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration timeout;

    @Autowired
    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafka) {
        this(kafka, DEFAULT_TOPIC, DEFAULT_TIMEOUT);
    }

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafka, String topic, Duration timeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public PublishResult publish(OutboxEvent event) {
        Map<UUID, PublishResult> results = publishBatch(List.of(event));
        return results.getOrDefault(event.id(), PublishResult.RETRYABLE_FAILURE);
    }

    @Override
    public Map<UUID, PublishResult> publishBatch(List<OutboxEvent> events) {
        Map<UUID, PublishResult> results = new HashMap<>();
        if (events == null || events.isEmpty()) {
            return results;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (OutboxEvent event : events) {
            String key = event.aggregateId().toString();
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, event.payload());

            // CloudEvents binary mode headers
            record.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_id", event.id().toString().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_source", ("/merchants/" + event.merchantId() + "/payments").getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_type", ("com.portfolio.payments." + event.eventType().toLowerCase() + ".v1").getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_subject", key.getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_time", event.createdAt().toString().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("ce_merchantid", event.merchantId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("content-type", "application/json".getBytes(StandardCharsets.UTF_8)));

            CompletableFuture<Void> future = kafka.send(record)
                .thenAccept(sr -> {
                    log.info("OUTBOX-KAFKA published id={} type={} key={} partition={} offset={}",
                        event.id(), event.eventType(), key,
                        sr.getRecordMetadata().partition(), sr.getRecordMetadata().offset());
                    synchronized (results) {
                        results.put(event.id(), PublishResult.SUCCESS);
                    }
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("OUTBOX-KAFKA publish failed for event {}: {}", event.id(), cause.getMessage());
                    PublishResult result;
                    if (cause instanceof RecordTooLargeException || cause instanceof SerializationException) {
                        result = PublishResult.GIVE_UP;
                    } else {
                        result = PublishResult.RETRYABLE_FAILURE;
                    }
                    synchronized (results) {
                        results.put(event.id(), result);
                    }
                    return null;
                });

            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("OUTBOX-KAFKA batch publish timed out after {}ms", timeout.toMillis());
            for (OutboxEvent event : events) {
                results.putIfAbsent(event.id(), PublishResult.RETRYABLE_FAILURE);
            }
        } catch (Exception e) {
            log.error("OUTBOX-KAFKA batch error", e);
            for (OutboxEvent event : events) {
                results.putIfAbsent(event.id(), PublishResult.RETRYABLE_FAILURE);
            }
        }

        return results;
    }
}
