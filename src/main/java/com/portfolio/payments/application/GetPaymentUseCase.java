package com.portfolio.payments.application;

import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use case: lookup a Payment by id. Returns the domain aggregate so the
 * caller can map it to the response shape. Missing id raises 404.
 */
@Service
public class GetPaymentUseCase {

    private final PaymentRepository repository;

    public GetPaymentUseCase(PaymentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Payment byId(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}