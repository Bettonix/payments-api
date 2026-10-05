package com.portfolio.payments.application;

import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.application.port.EventSerializer;
import com.portfolio.payments.application.port.IdempotencyStore;
import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.IdempotencyPayloadMismatchException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentEvent;
import com.portfolio.payments.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Use case: criar um Payment novo respeitando idempotência.
 *
 * <p>C1 Fix: Usa {@code repository.insert()} que executa persist + flush síncrono,
 * garantindo que colisões concorrentes da chave de idempotência disparem
 * {@link DataIntegrityViolationException} dentro do bloco try/catch.</p>
 *
 * <p>C8 Fix: Valida o fingerprint do payload contra o registro existente.
 * Chave reutilizada com payload divergente lança {@link IdempotencyPayloadMismatchException} (HTTP 422).</p>
 *
 * <p>C3 Fix: Serializa domain events usando {@link EventSerializer}.</p>
 */
@Service
public class CreatePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final PaymentRepository repository;
    private final OutboxRepository outbox;
    private final PaymentMetrics metrics;
    private final EventSerializer serializer;
    private final IdempotencyStore idempotencyStore;

    @Autowired
    public CreatePaymentUseCase(PaymentRepository repository,
                                OutboxRepository outbox,
                                PaymentMetrics metrics,
                                EventSerializer serializer,
                                IdempotencyStore idempotencyStore) {
        this.repository = repository;
        this.outbox = outbox;
        this.metrics = metrics;
        this.serializer = serializer;
        this.idempotencyStore = idempotencyStore != null ? idempotencyStore : IdempotencyStore.noop();
    }

    public CreatePaymentUseCase(PaymentRepository repository,
                                OutboxRepository outbox,
                                PaymentMetrics metrics,
                                EventSerializer serializer) {
        this(repository, outbox, metrics, serializer, IdempotencyStore.noop());
    }

    public CreatePaymentUseCase(PaymentRepository repository,
                                OutboxRepository outbox,
                                PaymentMetrics metrics) {
        this(repository, outbox, metrics, event -> "{\"eventType\":\"" + event.eventType() + "\"}", IdempotencyStore.noop());
    }

    @Transactional
    public Result execute(String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        return execute(Payment.DEFAULT_MERCHANT_ID, idempotencyKey, payerId, payeeId, amount);
    }

    @Transactional
    public Result execute(String merchantId, String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        String fingerprint = RequestFingerprint.compute(payerId, payeeId, amount);

        // 1. Tentar adquirir lock distribuído no Redis (ou fail-open se Redis indisponível)
        IdempotencyStore.AcquireResult acquireResult =
            idempotencyStore.tryAcquire(merchantId, idempotencyKey, fingerprint);

        if (acquireResult instanceof IdempotencyStore.AcquireResult.InProgress) {
            log.info("idempotency in-progress lock detected in redis: key={} merchant={}", idempotencyKey, merchantId);
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }

        if (acquireResult instanceof IdempotencyStore.AcquireResult.PayloadMismatch) {
            log.warn("idempotency payload mismatch in redis: key={} merchant={}", idempotencyKey, merchantId);
            throw new IdempotencyPayloadMismatchException(idempotencyKey);
        }

        if (acquireResult instanceof IdempotencyStore.AcquireResult.Completed completed) {
            return repository.findById(completed.paymentId())
                .filter(p -> p.merchantId().equals(merchantId))
                .map(existing -> {
                    log.info("idempotent replay from redis completed key: key={} paymentId={} merchant={}",
                        idempotencyKey, existing.id(), merchantId);
                    metrics.recordReplayed();
                    return Result.replayed(existing);
                })
                .orElseGet(() -> createWithDatabaseAndComplete(merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount));
        }

        return createWithDatabaseAndComplete(merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount);
    }

    private Result createWithDatabaseAndComplete(String merchantId, String idempotencyKey, String fingerprint,
                                                 UUID payerId, UUID payeeId, Money amount) {
        var existingOpt = repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existingOpt.isPresent()) {
            Payment existing = existingOpt.get();
            if (existing.requestFingerprint() != null && !existing.requestFingerprint().equals(fingerprint)) {
                log.warn("idempotency key reused with mismatched payload: key={} merchant={}",
                    idempotencyKey, merchantId);
                throw new IdempotencyPayloadMismatchException(idempotencyKey);
            }
            log.info("idempotent replay: key={} paymentId={} merchant={}",
                idempotencyKey, existing.id(), merchantId);
            metrics.recordReplayed();
            idempotencyStore.complete(merchantId, idempotencyKey, existing.id(), fingerprint);
            return Result.replayed(existing);
        }

        boolean success = false;
        try {
            Payment payment = Payment.create(merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount);
            Payment saved;
            try {
                saved = repository.insert(payment);
            } catch (DataIntegrityViolationException ex) {
                log.info("idempotency race detected on key={} merchant={}", idempotencyKey, merchantId);
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }

            List<PaymentEvent> events = payment.pullEvents();
            for (PaymentEvent event : events) {
                String payload = serializer.serialize(event);
                outbox.save(OutboxEvent.create(saved.merchantId(), "Payment", saved.id(), event.eventType(), payload));
            }

            log.info("created payment id={} amount={} {} merchant={}",
                saved.id(), saved.amount().amount(), saved.amount().currency().getCurrencyCode(), saved.merchantId());
            metrics.recordCreated(saved.amount().currency().getCurrencyCode());

            idempotencyStore.complete(merchantId, idempotencyKey, saved.id(), fingerprint);
            success = true;
            return Result.created(saved);
        } finally {
            if (!success) {
                idempotencyStore.release(merchantId, idempotencyKey);
            }
        }
    }

    public sealed interface Result {
        Payment payment();
        static Result created(Payment p) { return new Created(p); }
        static Result replayed(Payment p) { return new Replayed(p); }
        record Created(Payment payment) implements Result {}
        record Replayed(Payment payment) implements Result {}
    }
}