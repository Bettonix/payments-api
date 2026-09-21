package com.portfolio.payments;

import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PaymentRepository;
import com.portfolio.payments.domain.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests para use cases de leitura e transição. Usa um repositório
 * fake em memória — não sobe Spring, não precisa de Postgres.
 *
 * <p>Valida que o use case:</p>
 * <ul>
 *   <li>delega corretamente ao agregado para validar a state machine</li>
 *   <li>persiste o resultado da transição</li>
 *   <li>mapeia inexistência em PaymentNotFoundException</li>
 *   <li>propaga InvalidPaymentTransitionException sem tentar persistir</li>
 * </ul>
 */
class PaymentUseCasesTest {

    private InMemoryPaymentRepository repo;
    private InMemoryOutboxRepository outbox;
    private PaymentMetrics metrics;
    private GetPaymentUseCase getUseCase;
    private TransitionPaymentUseCase transitionUseCase;

    @BeforeEach
    void setUp() {
        repo = new InMemoryPaymentRepository();
        outbox = new InMemoryOutboxRepository();
        metrics = new PaymentMetrics(new SimpleMeterRegistry());
        getUseCase = new GetPaymentUseCase(repo);
        transitionUseCase = new TransitionPaymentUseCase(repo, outbox, metrics);
    }

    @Test
    void getReturnsExistingPayment() {
        Payment created = Payment.create("k1", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        repo.save(created);

        Payment found = getUseCase.byId(created.id());

        assertEquals(created.id(), found.id());
        assertEquals(PaymentStatus.PENDING, found.status());
    }

    @Test
    void getThrowsWhenNotFound() {
        assertThrows(PaymentNotFoundException.class,
            () -> getUseCase.byId(UUID.randomUUID()));
    }

    @Test
    void transitionWalksHappyPath() {
        Payment created = Payment.create("k2", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        repo.save(created);

        Payment afterAuth = transitionUseCase.execute(created.id(), TransitionPaymentUseCase.Transition.AUTHORIZE, null);
        assertEquals(PaymentStatus.AUTHORIZED, afterAuth.status());

        Payment afterCapture = transitionUseCase.execute(created.id(), TransitionPaymentUseCase.Transition.CAPTURE, null);
        assertEquals(PaymentStatus.CAPTURED, afterCapture.status());

        Payment afterSettle = transitionUseCase.execute(created.id(), TransitionPaymentUseCase.Transition.SETTLE, null);
        assertEquals(PaymentStatus.SETTLED, afterSettle.status());
    }

    @Test
    void transitionFailRecordsReason() {
        Payment created = Payment.create("k3", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        repo.save(created);

        Payment failed = transitionUseCase.execute(
            created.id(), TransitionPaymentUseCase.Transition.FAIL, "fraud-blocked");

        assertEquals(PaymentStatus.FAILED, failed.status());
        assertEquals("fraud-blocked", failed.failureReason());
    }

    @Test
    void transitionRejectsInvalidAndDoesNotPersist() {
        Payment created = Payment.create("k4", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        repo.save(created);

        // PENDING -> SETTLE is invalid
        assertThrows(InvalidPaymentTransitionException.class,
            () -> transitionUseCase.execute(created.id(), TransitionPaymentUseCase.Transition.SETTLE, null));

        // state is unchanged
        Payment reloaded = repo.findById(created.id()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, reloaded.status());
    }

    @Test
    void transitionOnUnknownIdThrowsNotFound() {
        assertThrows(PaymentNotFoundException.class,
            () -> transitionUseCase.execute(UUID.randomUUID(), TransitionPaymentUseCase.Transition.AUTHORIZE, null));
    }

    @Test
    void transitionEmitsOutboxEvent() {
        Payment created = Payment.create("k5", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        repo.save(created);

        transitionUseCase.execute(created.id(), TransitionPaymentUseCase.Transition.AUTHORIZE, null);

        List<OutboxEvent> pending = outbox.fetchPendingBatch(10);
        assertEquals(1, pending.size(), "transition should emit one outbox event");
        OutboxEvent ev = pending.get(0);
        assertEquals("Payment", ev.aggregateType());
        assertEquals("PaymentTransitioned", ev.eventType());
        assertEquals(created.id(), ev.aggregateId());
        assertTrue(ev.payload().contains("\"from\":\"PENDING\""));
        assertTrue(ev.payload().contains("\"to\":\"AUTHORIZED\""));
    }

    /** Minimal in-memory implementation of the domain port for unit tests. */
    private static class InMemoryPaymentRepository implements PaymentRepository {
        private final Map<UUID, Payment> store = new HashMap<>();
        private final Map<String, Payment> byKey = new HashMap<>();

        @Override
        public Payment save(Payment payment) {
            store.put(payment.id(), payment);
            byKey.put(payment.idempotencyKey(), payment);
            return payment;
        }

        @Override
        public Optional<Payment> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Payment> findByIdempotencyKey(String key) {
            return Optional.ofNullable(byKey.get(key));
        }
    }

    /** Minimal in-memory outbox for unit tests. */
    private static class InMemoryOutboxRepository implements OutboxRepository {
        private final Map<java.util.UUID, OutboxEvent> store = new HashMap<>();

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
    }
}