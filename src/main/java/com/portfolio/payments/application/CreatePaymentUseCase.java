package com.portfolio.payments.application;

import com.portfolio.payments.domain.Money;
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
 */
@Service
public class CreatePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final PaymentRepository repository;

    public CreatePaymentUseCase(PaymentRepository repository) {
        this.repository = repository;
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
                log.info("created payment id={} amount={} {}",
                    saved.id(), saved.amount().amount(), saved.amount().currency().getCurrencyCode());
                return Result.created(saved);
            });
    }

    public sealed interface Result {
        Payment payment();
        static Result created(Payment p) { return new Created(p); }
        static Result replayed(Payment p) { return new Replayed(p); }
        record Created(Payment payment) implements Result {}
        record Replayed(Payment payment) implements Result {}
    }
}