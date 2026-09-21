package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests do {@link KafkaPublisher}. Usa mock do KafkaTemplate — não
 * precisa de broker rodando.
 *
 * <p>Cobre os 3 caminhos de retorno:
 *  - sucesso (broker confirma)
 *  - timeout (broker não responde em 5s)
 *  - falha de execução (broker rejeita)</p>
 */
class KafkaPublisherTest {

    private KafkaTemplate<String, String> kafka;
    private KafkaPublisher publisher;
    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        publisher = new KafkaPublisher(kafka, "test.topic");
        Payment p = Payment.create("k1", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        event = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{\"test\":true}");
    }

    @Test
    void publishesSuccessfully() {
        SendResult<String, String> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, String>> future =
            CompletableFuture.completedFuture(sendResult);

        when(kafka.send(eq("test.topic"), eq(event.aggregateId().toString()), eq("{\"test\":true}")))
            .thenReturn(future);

        OutboxPublisher.PublishResult result = publisher.publish(event);

        assertEquals(OutboxPublisher.PublishResult.SUCCESS, result);
        verify(kafka, times(1)).send(any(String.class), any(String.class), any(String.class));
    }

    @Test
    void returnsRetryableOnTimeout() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        // nunca completa — simula timeout

        when(kafka.send(any(String.class), any(String.class), any(String.class)))
            .thenReturn(future);

        OutboxPublisher.PublishResult result = publisher.publish(event);

        assertEquals(OutboxPublisher.PublishResult.RETRYABLE_FAILURE, result);
    }

    @Test
    void returnsRetryableOnExecutionFailure() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException(
            new RuntimeException("broker rejected")));

        when(kafka.send(any(String.class), any(String.class), any(String.class)))
            .thenReturn(future);

        OutboxPublisher.PublishResult result = publisher.publish(event);

        assertEquals(OutboxPublisher.PublishResult.RETRYABLE_FAILURE, result);
    }
}
