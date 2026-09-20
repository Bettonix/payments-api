package com.portfolio.payments.application;

import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use case: criar um Payment novo respeitando idempotência.
 *
 * <p>Se o header {@code Idempotency-Key} já existir, devolve o payment
 * existente em vez de criar duplicado. Esse é o coração do contrato
 * idempotency-safe: cliente pode retentar com segurança.</p>
 *
 * <p>Emite também um evento outbox {@code PaymentCreated} na mesma
 * transação, para publicação assíncrona via {@link com.portfolio.payments.application.outbox.OutboxRelay}.</p>
 */
@Service
public class CreatePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final PaymentRepository repository;
    private final OutboxRepository outbox;

    public CreatePaymentUseCase(PaymentRepository repository, OutboxRepository outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    @Transactional
    public Result execute(String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        return repository.findByIdempotencyKey(idempotencyKey)
            .map(existing -> {
                log.info("idempotent replay: key={} paymentId={}", idempotencyKey, existing.id());
                return Result.replayed(existing);
            })
            .orElseGet(() -> {
                Payment payment = Payment.create(idempotencyKey, payerId, payeeId, amount);
                Payment saved = repository.save(payment);

                // outbox event in the same TX — guaranteed at-least-once
                String payload = buildCreatedPayload(saved);
                outbox.save(OutboxEvent.create("Payment", saved.id(), "PaymentCreated", payload));

                log.info("created payment id={} amount={} {}",
                    saved.id(), saved.amount().amount(), saved.amount().currency().getCurrencyCode());
                return Result.created(saved);
            });
    }

    private String buildCreatedPayload(Payment p) {
        // Minimal hand-rolled JSON to avoid pulling a JSON lib into the
        // application layer. Postgres stores it as jsonb regardless.
        return "{"
            + "\"id\":\"" + p.id() + "\","
            + "\"payerId\":\"" + p.payerId() + "\","
            + "\"payeeId\":\"" + p.payeeId() + "\","
            + "\"amount\":\"" + p.amount().amount().toPlainString() + "\","
            + "\"currency\":\"" + p.amount().currency().getCurrencyCode() + "\","
            + "\"status\":\"" + p.status().name() + "\""
            + "}";
    }

    public sealed interface Result {
        Payment payment();
        static Result created(Payment p) { return new Created(p); }
        static Result replayed(Payment p) { return new Replayed(p); }
        record Created(Payment payment) implements Result {}
        record Replayed(Payment payment) implements Result {}
    }
}