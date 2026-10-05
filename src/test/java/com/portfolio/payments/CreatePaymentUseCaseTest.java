package com.portfolio.payments;

import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.IdempotencyPayloadMismatchException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes do {@link CreatePaymentUseCase} cobrindo o caminho de idempotência,
 * fingerprinting do payload e corrida de concorrência com flush imediato.
 */
class CreatePaymentUseCaseTest {

    private UniqueConstraintPaymentRepository repo;
    private InMemoryOutboxRepository outbox;
    private PaymentMetrics metrics;
    private CreatePaymentUseCase createUseCase;
    private GetPaymentUseCase getUseCase;
    private TransitionPaymentUseCase transitionUseCase;

    @BeforeEach
    void setUp() {
        repo = new UniqueConstraintPaymentRepository();
        outbox = new InMemoryOutboxRepository();
        metrics = new PaymentMetrics(new SimpleMeterRegistry());
        createUseCase = new CreatePaymentUseCase(repo, outbox, metrics);
        getUseCase = new GetPaymentUseCase(repo);
        transitionUseCase = new TransitionPaymentUseCase(repo, outbox, metrics);
    }

    @Test
    void firstCreateReturnsCreated() {
        var result = createUseCase.execute("k1", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        assertInstanceOf(CreatePaymentUseCase.Result.Created.class, result);
        assertEquals("k1", result.payment().idempotencyKey());
    }

    @Test
    void replayWithSameKeyAndSamePayloadReturnsExistingPayment() {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();

        var first = createUseCase.execute("k2", payer, payee, Money.of(100, "BRL"));
        var second = createUseCase.execute("k2", payer, payee, Money.of(100, "BRL"));

        assertInstanceOf(CreatePaymentUseCase.Result.Replayed.class, second);
        assertEquals(first.payment().id(), second.payment().id(), "replay must return same payment id");
    }

    @Test
    void replayWithSameKeyAndDifferentPayloadThrowsMismatchException() {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();

        createUseCase.execute("k-diff", payer, payee, Money.of(100, "BRL"));

        assertThrows(IdempotencyPayloadMismatchException.class,
            () -> createUseCase.execute("k-diff", payer, payee, Money.of(200, "BRL")),
            "Reusing key with different amount must throw 422 mismatch exception (C8)");
    }

    @Test
    void concurrentCreateWithSameKeyThrowsIdempotencyConflict() {
        String key = "k3";
        repo.seedExisting(key);
        repo.simulateRace();

        IdempotencyKeyConflictException ex = assertThrows(
            IdempotencyKeyConflictException.class,
            () -> createUseCase.execute(key, UUID.randomUUID(), UUID.randomUUID(), Money.of(50, "BRL")));

        assertEquals(key, ex.idempotencyKey());
    }

    @Test
    void createdPaymentEmitsOutboxEvent() {
        createUseCase.execute("k4", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        List<OutboxEvent> pending = outbox.fetchPendingBatch(10);
        assertEquals(1, pending.size(), "create should emit one outbox event");
        assertEquals("PaymentCreated", pending.get(0).eventType());
    }

    private static class UniqueConstraintPaymentRepository implements PaymentRepository {
        private final Map<UUID, Payment> store = new HashMap<>();
        private final Map<String, Payment> byKey = new HashMap<>();
        private boolean raceMode = false;

        void simulateRace() {
            this.raceMode = true;
        }

        void seedExisting(String key) {
            Payment p = Payment.create(key, UUID.randomUUID(), UUID.randomUUID(), Money.of(1, "BRL"));
            store.put(p.id(), p);
            byKey.put(key, p);
        }

        @Override
        public Payment insert(Payment payment) {
            if (byKey.containsKey(payment.idempotencyKey())) {
                throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"payments_idempotency_key\"");
            }
            store.put(payment.id(), payment);
            byKey.put(payment.idempotencyKey(), payment);
            return payment;
        }

        @Override
        public Payment save(Payment payment) {
            return insert(payment);
        }

        @Override
        public Optional<Payment> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Payment> findByIdempotencyKey(String key) {
            if (raceMode) {
                return Optional.empty();
            }
            return Optional.ofNullable(byKey.get(key));
        }

        @Override
        public Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String key) {
            return findByIdempotencyKey(key);
        }
    }

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
