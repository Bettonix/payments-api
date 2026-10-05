package com.portfolio.payments.application;

import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.application.port.EventSerializer;
import com.portfolio.payments.application.port.IdempotencyStore;
import com.portfolio.payments.application.port.PaymentIngressCommand;
import com.portfolio.payments.application.port.PaymentIngressPublisher;
import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.IdempotencyPayloadMismatchException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Use case: ingestão assíncrona de pagamentos de alta performance inspirada no PIX / RFC 7240.
 *
 * <p>Valida o schema e adquire o lock no Redis (&lt; 2ms). Se for replay, retorna a entidade existente.
 * Se for nova intenção, emite o comando de ingestão para o Kafka particionado pelo payerId e
 * retorna imediatamente com status ACCEPTED.</p>
 */
@Service
public class CreatePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final PaymentRepository repository;
    private final PaymentMetrics metrics;
    private final IdempotencyStore idempotencyStore;
    private final PaymentIngressPublisher ingressPublisher;

    @Autowired
    public CreatePaymentUseCase(PaymentRepository repository,
                                PaymentMetrics metrics,
                                IdempotencyStore idempotencyStore,
                                PaymentIngressPublisher ingressPublisher) {
        this.repository = repository;
        this.metrics = metrics;
        this.idempotencyStore = idempotencyStore != null ? idempotencyStore : IdempotencyStore.noop();
        this.ingressPublisher = ingressPublisher;
    }

    public CreatePaymentUseCase(PaymentRepository repository,
                                OutboxRepository outbox,
                                PaymentMetrics metrics,
                                EventSerializer serializer,
                                IdempotencyStore idempotencyStore) {
        this(repository, metrics, idempotencyStore,
            cmd -> new PaymentIngressService(repository, outbox, metrics, serializer, idempotencyStore).process(cmd));
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
                .orElseGet(() -> ingestAndAccept(merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount));
        }

        return ingestAndAccept(merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount);
    }

    private Result ingestAndAccept(String merchantId, String idempotencyKey, String fingerprint,
                                   UUID payerId, UUID payeeId, Money amount) {
        var existingOpt = repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existingOpt.isPresent()) {
            Payment existing = existingOpt.get();
            if (existing.requestFingerprint() != null && !existing.requestFingerprint().equals(fingerprint)) {
                log.warn("idempotency key reused with mismatched payload: key={} merchant={}",
                    idempotencyKey, merchantId);
                throw new IdempotencyPayloadMismatchException(idempotencyKey);
            }
            log.info("idempotent replay from repository: key={} paymentId={} merchant={}",
                idempotencyKey, existing.id(), merchantId);
            metrics.recordReplayed();
            idempotencyStore.complete(merchantId, idempotencyKey, existing.id(), fingerprint);
            return Result.replayed(existing);
        }

        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.createWithId(paymentId, merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount);

        PaymentIngressCommand command = new PaymentIngressCommand(
            paymentId,
            merchantId,
            idempotencyKey,
            fingerprint,
            payerId,
            payeeId,
            amount,
            Instant.now()
        );

        ingressPublisher.publish(command);
        metrics.recordCreated(payment.amount().currency().getCurrencyCode());

        log.info("accepted async payment id={} amount={} {} merchant={}",
            payment.id(), payment.amount().amount(), payment.amount().currency().getCurrencyCode(), merchantId);

        return Result.accepted(payment);
    }

    public sealed interface Result {
        Payment payment();
        static Result accepted(Payment p) { return new Accepted(p); }
        static Result created(Payment p) { return new Accepted(p); }
        static Result replayed(Payment p) { return new Replayed(p); }
        record Accepted(Payment payment) implements Result {}
        record Created(Payment payment) implements Result {}
        record Replayed(Payment payment) implements Result {}
    }
}