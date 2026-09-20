package com.portfolio.payments.application;

import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PaymentRepository;
import com.portfolio.payments.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use case: drive a Payment through its state machine.
 *
 * <p>Resolves the payment by id, asks the aggregate to apply the transition
 * (which enforces the state machine), then persists. The whole flow runs
 * in a single transaction so concurrent callers see a consistent state.</p>
 *
 * <p>If the requested transition is invalid for the current state, the
 * aggregate throws {@link com.portfolio.payments.domain.InvalidPaymentTransitionException}
 * and the transaction rolls back.</p>
 */
@Service
public class TransitionPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransitionPaymentUseCase.class);

    private final PaymentRepository repository;

    public TransitionPaymentUseCase(PaymentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Payment execute(UUID id, Transition transition, String reason) {
        Payment payment = repository.findById(id)
            .orElseThrow(() -> new PaymentNotFoundException(id));

        PaymentStatus before = payment.status();
        applyTransition(payment, transition, reason);
        Payment saved = repository.save(payment);

        log.info("payment transitioned id={} {} -> {} via {}",
            saved.id(), before, saved.status(), transition);

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

    /** Allowed operations exposed via PATCH /payments/{id}/{transition}. */
    public enum Transition {
        AUTHORIZE, CAPTURE, SETTLE, FAIL, CANCEL
    }
}