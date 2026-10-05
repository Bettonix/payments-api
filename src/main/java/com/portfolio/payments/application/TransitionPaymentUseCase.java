package com.portfolio.payments.application;

import com.portfolio.payments.application.metrics.PaymentMetrics;
import com.portfolio.payments.application.port.EventSerializer;
import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentEvent;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PaymentRepository;
import com.portfolio.payments.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Use case: drive a Payment through its state machine.
 *
 * <p>Resolves the payment by id, asks the aggregate to apply the transition
 * (which enforces the state machine), then persists.
 *
 * <p>C3 Fix: Serializa domain events através de {@link EventSerializer},
 * eliminando montagem manual de JSON.</p>
 */
@Service
public class TransitionPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransitionPaymentUseCase.class);

    private final PaymentRepository repository;
    private final OutboxRepository outbox;
    private final PaymentMetrics metrics;
    private final EventSerializer serializer;

    @org.springframework.beans.factory.annotation.Autowired
    public TransitionPaymentUseCase(PaymentRepository repository,
                                    OutboxRepository outbox,
                                    PaymentMetrics metrics,
                                    EventSerializer serializer) {
        this.repository = repository;
        this.outbox = outbox;
        this.metrics = metrics;
        this.serializer = serializer;
    }

    public TransitionPaymentUseCase(PaymentRepository repository,
                                    OutboxRepository outbox,
                                    PaymentMetrics metrics) {
        this(repository, outbox, metrics, event -> "{\"eventType\":\"" + event.eventType() + "\"}");
    }

    @Transactional
    public Payment execute(UUID id, Transition transition, String reason) {
        Payment payment = repository.findById(id)
            .orElseThrow(() -> new PaymentNotFoundException(id));

        PaymentStatus before = payment.status();
        applyTransition(payment, transition, reason);
        Payment saved = repository.save(payment);

        List<PaymentEvent> events = payment.pullEvents();
        for (PaymentEvent event : events) {
            String payload = serializer.serialize(event);
            outbox.save(OutboxEvent.create(saved.merchantId(), "Payment", saved.id(), event.eventType(), payload));
        }

        log.info("payment transitioned id={} {} -> {} via {}",
            saved.id(), before, saved.status(), transition);
        metrics.recordTransition(before, saved.status());

        return saved;
    }

    private void applyTransition(Payment payment, Transition transition, String reason) {
        switch (transition) {
            case AUTHORIZE -> payment.authorize();
            case CAPTURE   -> payment.capture();
            case SETTLE    -> payment.settle();
            case FAIL      -> payment.fail(reason != null ? reason : "unspecified");
            case CANCEL    -> payment.cancel();
        }
    }

    /** Allowed operations exposed via REST. */
    public enum Transition {
        AUTHORIZE, CAPTURE, SETTLE, FAIL, CANCEL
    }
}