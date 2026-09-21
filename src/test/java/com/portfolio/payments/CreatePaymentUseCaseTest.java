package com.portfolio.payments;

import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes do {@link CreatePaymentUseCase} cobrindo o caminho de idempotência,
 * incluindo o race do SELECT+INSERT capturado pela UNIQUE constraint.
 *
 * <p>O ADR-0002 documenta que o fast-path de replay (SELECT antes do INSERT)
 * cobre a maioria dos retries. Esse teste cobre o corner case: duas threads
 * passando o SELECT ao mesmo tempo e colidindo no INSERT — o segundo
 * recebe um 409 via {@link IdempotencyKeyConflictException}.</p>
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
    void replayWithSameKeyReturnsExistingPayment() {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();

        var first = createUseCase.execute("k2", payer, payee, Money.of(100, "BRL"));
        var second = createUseCase.execute("k2", payer, payee, Money.of(100, "BRL"));

        assertInstanceOf(CreatePaymentUseCase.Result.Replayed.class, second);
        assertEquals(first.payment().id(), second.payment().id(), "replay must return same payment id");
    }

    @Test
    void concurrentCreateWithSameKeyThrowsIdempotencyConflict() {
        // Simula o race: SELECT antes do INSERT não vê a chave (outra thread já persistiu
        // entre o SELECT e o INSERT desta thread), INSERT falha por UNIQUE constraint.
        String key = "k3";
        repo.seedExisting(key);    // outra thread persistiu com essa chave
        repo.simulateRace();        // nosso SELECT não enxerga o concorrente

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

    /** In-memory repository que simula UNIQUE constraint em idempotency_key.
     *  O modo "race" faz findByIdempotencyKey sempre retornar vazio,
     *  simulando o cenário em que outra thread criou com a mesma chave
     *  entre nosso SELECT e nosso INSERT — só o save detecta via UNIQUE. */
    private static class UniqueConstraintPaymentRepository implements PaymentRepository {
        private final Map<UUID, Payment> store = new HashMap<>();
        private final Map<String, Payment> byKey = new HashMap<>();
        private boolean raceMode = false;

        void simulateRace() {
            this.raceMode = true;
        }

        /** pré-popula como se outra thread já tivesse persistido com a chave. */
        void seedExisting(String key) {
            Payment p = Payment.create(key, UUID.randomUUID(), UUID.randomUUID(), Money.of(1, "BRL"));
            store.put(p.id(), p);
            byKey.put(key, p);
        }

        @Override
        public Payment save(Payment payment) {
            if (byKey.containsKey(payment.idempotencyKey())) {
                throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"payments_idempotency_key\"");
            }
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
            // No race mode: simula que o SELECT antes do INSERT não enxerga o concorrente
            // (em produção, isso acontece porque as duas requests paralelas executam SELECT antes do INSERT)
            if (raceMode) {
                return Optional.empty();
            }
            return Optional.ofNullable(byKey.get(key));
        }
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
    }
}
