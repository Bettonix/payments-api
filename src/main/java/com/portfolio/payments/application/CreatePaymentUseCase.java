package com.portfolio.payments.application;

import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.application.port.EventSerializer;
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

    public CreatePaymentUseCase(PaymentRepository repository,
                                OutboxRepository outbox,
                                PaymentMetrics metrics,
                                EventSerializer serializer) {
        this.repository = repository;
        this.outbox = outbox;
        this.metrics = metrics;
        this.serializer = serializer;
    }

    public CreatePaymentUseCase(PaymentRepository repository,
                                OutboxRepository outbox,
                                PaymentMetrics metrics) {
        this(repository, outbox, metrics, event -> "{\"eventType\":\"" + event.eventType() + "\"}");
    }

    @Transactional
    public Result execute(String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        return execute(Payment.DEFAULT_MERCHANT_ID, idempotencyKey, payerId, payeeId, amount);
    }

    @Transactional
    public Result execute(String merchantId, String idempotencyKey, UUID payerId, UUID payeeId, Money amount) {
        String fingerprint = RequestFingerprint.compute(payerId, payeeId, amount);

        return repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
            .map(existing -> {
                // C8: Validação de reuso com payload diferente
                if (existing.requestFingerprint() != null && !existing.requestFingerprint().equals(fingerprint)) {
                    log.warn("idempotency key reused with mismatched payload: key={} merchant={}",
                        idempotencyKey, merchantId);
                    throw new IdempotencyPayloadMismatchException(idempotencyKey);
                }
                log.info("idempotent replay: key={} paymentId={} merchant={}",
                    idempotencyKey, existing.id(), merchantId);
                metrics.recordReplayed();
                return Result.replayed(existing);
            })
            .orElseGet(() -> {
                Payment payment = Payment.create(merchantId, idempotencyKey, fingerprint, payerId, payeeId, amount);
                Payment saved;
                try {
                    // C1 Fix: insert com flush imediato dentro da transação
                    saved = repository.insert(payment);
                } catch (DataIntegrityViolationException ex) {
                    log.info("idempotency race detected on key={} merchant={}", idempotencyKey, merchantId);
                    throw new IdempotencyKeyConflictException(idempotencyKey);
                }

                // C3 Fix: serialização segura dos domain events
                List<PaymentEvent> events = payment.pullEvents();
                for (PaymentEvent event : events) {
                    String payload = serializer.serialize(event);
                    outbox.save(OutboxEvent.create(saved.merchantId(), "Payment", saved.id(), event.eventType(), payload));
                }

                log.info("created payment id={} amount={} {} merchant={}",
                    saved.id(), saved.amount().amount(), saved.amount().currency().getCurrencyCode(), saved.merchantId());
                metrics.recordCreated(saved.amount().currency().getCurrencyCode());
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