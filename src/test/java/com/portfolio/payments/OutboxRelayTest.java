package com.portfolio.payments;

import com.portfolio.payments.application.outbox.OutboxPublisher;
import com.portfolio.payments.application.outbox.OutboxRelay;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes do OutboxRelay sem Spring — validam a lógica de drain + circuit breaker
 * via mock do publisher.
 *
 * <p>Como o @CircuitBreaker é processado via AOP, este teste unit não cobre o
 * comportamento do circuit breaker de verdade. Pra isso, precisaríamos de um
 * teste de integração subindo o contexto Spring. O teste aqui cobre o contrato
 * do relay: estado dos eventos depois de drain, contagem de tentativas.</p>
 */
class OutboxRelayTest {

    private InMemoryOutboxRepository outbox;
    private OutboxPublisher publisher;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        outbox = new InMemoryOutboxRepository();
        publisher = Mockito.mock(OutboxPublisher.class);
        relay = new OutboxRelay(outbox, publisher);
    }

    @Test
    void drainWithNoPendingEventsDoesNothing() {
        when(publisher.publish(any())).thenReturn(OutboxPublisher.PublishResult.SUCCESS);

        relay.drain();

        verify(publisher, never()).publish(any());
    }

    @Test
    void drainMarksSuccessfulEventsAsPublished() {
        Payment p = Payment.create("k1", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publish(any())).thenReturn(OutboxPublisher.PublishResult.SUCCESS);

        relay.drain();

        OutboxEvent reloaded = outbox.fetchPendingBatch(10).isEmpty()
            ? null
            : outbox.fetchPendingBatch(10).get(0);
        // eventos SUCCESS não devem aparecer em pending
        List<OutboxEvent> pendingAfter = outbox.fetchPendingBatch(10);
        assertTrue(pendingAfter.stream().noneMatch(e -> e.id().equals(ev.id())),
            "successful event should no longer be pending");
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void drainMarksRetryableFailureAsFailed() {
        Payment p = Payment.create("k2", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publish(any())).thenReturn(OutboxPublisher.PublishResult.RETRYABLE_FAILURE);

        relay.drain();

        // ainda está pending (não atinge MAX_ATTEMPTS = 5 numa só call)
        List<OutboxEvent> pending = outbox.fetchPendingBatch(10);
        assertEquals(1, pending.size());
        assertEquals(1, pending.get(0).attemptCount(), "attempt count incremented");
        // RETRYABLE_FAILURE marca lastError mas mantém status PENDING — só MAX_ATTEMPTS muda pra GAVE_UP
        assertEquals(OutboxEvent.Status.PENDING, pending.get(0).status());
        assertEquals("retryable failure", pending.get(0).lastError());
    }

    @Test
    void drainGivesUpAfterMaxAttempts() {
        Payment p = Payment.create("k3", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publish(any())).thenReturn(OutboxPublisher.PublishResult.RETRYABLE_FAILURE);

        // drena 5 vezes — MAX_ATTEMPTS = 5
        for (int i = 0; i < 5; i++) {
            relay.drain();
        }

        List<OutboxEvent> pending = outbox.fetchPendingBatch(10);
        assertTrue(pending.isEmpty(), "after max attempts event should not be pending");
    }

    @Test
    void drainHandlesRuntimeExceptionFromPublisher() {
        Payment p = Payment.create("k4", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publish(any())).thenThrow(new RuntimeException("kafka unreachable"));

        relay.drain();

        // ainda pending (exceto se já estourou max)
        List<OutboxEvent> pending = outbox.fetchPendingBatch(10);
        assertEquals(1, pending.size(), "runtime exception marks as failed but keeps pending");
        assertEquals(1, pending.get(0).attemptCount());
    }

    @Test
    void countPendingReportsQueueDepth() {
        // 3 eventos pendentes
        for (int i = 0; i < 3; i++) {
            Payment p = Payment.create("k" + i, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
            outbox.save(OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}"));
        }

        assertEquals(3, outbox.countPending());
    }

    /** In-memory outbox reutilizado. */
    private static class InMemoryOutboxRepository implements OutboxRepository {
        private final Map<UUID, OutboxEvent> store = new HashMap<>();

        @Override
        public OutboxEvent save(OutboxEvent event) {
            store.put(event.id(), event);
            return event;
        }

        @Override
        public List<OutboxEvent> fetchPendingBatch(int limit) {
            return store.values().stream()
                .filter(e -> e.status() == OutboxEvent.Status.PENDING)
                .sorted(java.util.Comparator.comparing(OutboxEvent::createdAt))
                .limit(limit)
                .toList();
        }

        @Override
        public long countPending() {
            return store.values().stream()
                .filter(e -> e.status() == OutboxEvent.Status.PENDING)
                .count();
        }
    }
}
