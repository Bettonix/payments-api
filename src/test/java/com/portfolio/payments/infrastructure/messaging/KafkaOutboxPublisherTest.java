package com.portfolio.payments.infrastructure.messaging;

import com.portfolio.payments.application.outbox.OutboxPublisher;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.Payment;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test do KafkaOutboxPublisher com timeout rápido (50ms) e CloudEvents headers.
 */
class KafkaOutboxPublisherTest {

    private KafkaTemplate<String, String> kafka;
    private KafkaOutboxPublisher publisher;
    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        // Timeout rápido de 50ms para testes sem delay
        publisher = new KafkaOutboxPublisher(kafka, "test.topic", Duration.ofMillis(50));
        Payment p = Payment.create("k1", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        event = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{\"test\":true}");
    }

    @Test
    void publishesSuccessfullyWithCloudEventsHeaders() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("test.topic", 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(new ProducerRecord<>("test.topic", "key", "val"), metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);

        OutboxPublisher.PublishResult result = publisher.publish(event);

        assertEquals(OutboxPublisher.PublishResult.SUCCESS, result);
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void assertsCloudEventsHeadersFormat() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("test.topic", 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(new ProducerRecord<>("test.topic", "key", "val"), metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafka.send(captor.capture())).thenReturn(future);

        publisher.publish(event);

        ProducerRecord<String, String> record = captor.getValue();
        assertEquals("test.topic", record.topic());
        assertEquals(event.aggregateId().toString(), record.key());
        assertEquals("1.0", new String(record.headers().lastHeader("ce_specversion").value(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(event.id().toString(), new String(record.headers().lastHeader("ce_id").value(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("com.portfolio.payments.paymentcreated.v1", new String(record.headers().lastHeader("ce_type").value(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("/merchants/" + event.merchantId() + "/payments", new String(record.headers().lastHeader("ce_source").value(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void returnsRetryableOnTimeout() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);

        OutboxPublisher.PublishResult result = publisher.publish(event);

        assertEquals(OutboxPublisher.PublishResult.RETRYABLE_FAILURE, result);
    }

    @Test
    void returnsRetryableOnExecutionFailure() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker rejected"));

        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);

        OutboxPublisher.PublishResult result = publisher.publish(event);

        assertEquals(OutboxPublisher.PublishResult.RETRYABLE_FAILURE, result);
    }
}
