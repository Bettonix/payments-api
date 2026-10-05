package com.portfolio.payments;

import com.portfolio.payments.application.outbox.OutboxPublisher;
import com.portfolio.payments.application.outbox.OutboxRelay;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes do OutboxRelay validando o ciclo de claim, lease, retries e publicação.
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
        when(publisher.publishBatch(any())).thenReturn(Map.of());

        relay.drain();

        verify(publisher, never()).publishBatch(any());
    }

    @Test
    void drainMarksSuccessfulEventsAsPublished() {
        Payment p = Payment.create("k1", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publishBatch(any())).thenReturn(Map.of(ev.id(), OutboxPublisher.PublishResult.SUCCESS));

        relay.drain();

        List<OutboxEvent> pendingAfter = outbox.fetchPendingBatch(10);
        assertTrue(pendingAfter.stream().noneMatch(e -> e.id().equals(ev.id())),
            "successful event should no longer be pending");
        verify(publisher, times(1)).publishBatch(any());
    }

    @Test
    void drainSchedulesRetryOnFailure() {
        Payment p = Payment.create("k2", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publishBatch(any())).thenReturn(Map.of(ev.id(), OutboxPublisher.PublishResult.RETRYABLE_FAILURE));

        relay.drain();

        OutboxEvent stored = outbox.store.get(ev.id());
        assertNotNull(stored);
        assertEquals(1, stored.attemptCount(), "attempt count incremented");
        assertEquals(OutboxEvent.Status.PENDING, stored.status());
    }

    @Test
    void drainGivesUpAfterMaxAttempts() {
        Payment p = Payment.create("k3", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        OutboxEvent ev = OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}");
        outbox.save(ev);

        when(publisher.publishBatch(any())).thenReturn(Map.of(ev.id(), OutboxPublisher.PublishResult.GIVE_UP));

        relay.drain();

        OutboxEvent stored = outbox.store.get(ev.id());
        assertEquals(OutboxEvent.Status.FAILED, stored.status(), "GIVE_UP marks event as FAILED");
    }

    @Test
    void countPendingReportsQueueDepth() {
        for (int i = 0; i < 3; i++) {
            Payment p = Payment.create("k" + i, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
            outbox.save(OutboxEvent.create("Payment", p.id(), "PaymentCreated", "{}"));
        }

        assertEquals(3, outbox.countPending());
    }

    private static class InMemoryOutboxRepository implements OutboxRepository {
        final Map<UUID, OutboxEvent> store = new HashMap<>();

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
        public List<OutboxEvent> claimBatch(int limit, Duration lease) {
            return fetchPendingBatch(limit);
        }

        @Override
        public void markPublished(Collection<UUID> ids, Instant at) {
            for (UUID id : ids) {
                OutboxEvent ev = store.get(id);
                if (ev != null) ev.markPublished();
            }
        }

        @Override
        public void scheduleRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
            OutboxEvent ev = store.get(id);
            if (ev != null) ev.scheduleRetry(Duration.ofSeconds(1), error);
        }

        @Override
        public void markFailed(UUID id, int attempts, String error) {
            OutboxEvent ev = store.get(id);
            if (ev != null) ev.markGiveUp(error);
        }

        @Override
        public long countPending() {
            return store.values().stream()
                .filter(e -> e.status() == OutboxEvent.Status.PENDING)
                .count();
        }

        @Override
        public long countFailed() {
            return store.values().stream()
                .filter(e -> e.status() == OutboxEvent.Status.FAILED)
                .count();
        }

        @Override
        public int deletePublishedBefore(Instant cutoff, int limit) {
            return 0;
        }
    }
}
