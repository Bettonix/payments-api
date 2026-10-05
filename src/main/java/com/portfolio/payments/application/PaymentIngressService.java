package com.portfolio.payments.application;

import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.application.port.EventSerializer;
import com.portfolio.payments.application.port.IdempotencyStore;
import com.portfolio.payments.application.port.PaymentIngressCommand;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentEvent;
import com.portfolio.payments.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Serviço responsável por persistir a intenção de pagamento recebida via fila de ingestão,
 * inserindo o agregado no banco de dados, gravando o evento no Outbox e completando
 * o ciclo de idempotência.
 */
@Service
public class PaymentIngressService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIngressService.class);

    private final PaymentRepository repository;
    private final OutboxRepository outbox;
    private final PaymentMetrics metrics;
    private final EventSerializer serializer;
    private final IdempotencyStore idempotencyStore;

    @Autowired
    public PaymentIngressService(PaymentRepository repository,
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

    public PaymentIngressService(PaymentRepository repository,
                                 OutboxRepository outbox,
                                 PaymentMetrics metrics,
                                 EventSerializer serializer) {
        this(repository, outbox, metrics, serializer, IdempotencyStore.noop());
    }

    @Transactional
    public Payment process(PaymentIngressCommand command) {
        var existing = repository.findByMerchantIdAndIdempotencyKey(command.merchantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            Payment p = existing.get();
            log.info("payment already persisted for idempotencyKey={}, skipping: id={}", command.idempotencyKey(), p.id());
            idempotencyStore.complete(command.merchantId(), command.idempotencyKey(), p.id(), command.fingerprint());
            return p;
        }

        long dbStartNanos = System.nanoTime();
        boolean success = false;
        try {
            Payment payment = Payment.createWithId(
                command.paymentId(),
                command.merchantId(),
                command.idempotencyKey(),
                command.fingerprint(),
                command.payerId(),
                command.payeeId(),
                command.amount()
            );

            Payment saved;
            try {
                saved = repository.insert(payment);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.info("idempotency race detected on key={} merchant={}", command.idempotencyKey(), command.merchantId());
                throw new com.portfolio.payments.domain.IdempotencyKeyConflictException(command.idempotencyKey());
            }

            List<PaymentEvent> events = payment.pullEvents();
            for (PaymentEvent event : events) {
                String payload = serializer.serialize(event);
                outbox.save(OutboxEvent.create(saved.merchantId(), "Payment", saved.id(), event.eventType(), payload));
            }

            log.info("persisted async payment id={} amount={} {} merchant={}",
                saved.id(), saved.amount().amount(), saved.amount().currency().getCurrencyCode(), saved.merchantId());
            metrics.recordCreated(saved.amount().currency().getCurrencyCode());

            idempotencyStore.complete(command.merchantId(), command.idempotencyKey(), saved.id(), command.fingerprint());
            success = true;

            long dbDuration = System.nanoTime() - dbStartNanos;
            metrics.recordDbPersistenceLatency(dbDuration);
            if (command.requestedAt() != null) {
                long e2eDuration = java.time.Duration.between(command.requestedAt(), java.time.Instant.now()).toNanos();
                if (e2eDuration > 0) {
                    metrics.recordE2eLatency(e2eDuration);
                }
            }

            return saved;
        } finally {
            if (!success) {
                idempotencyStore.release(command.merchantId(), command.idempotencyKey());
            }
        }
    }
}
