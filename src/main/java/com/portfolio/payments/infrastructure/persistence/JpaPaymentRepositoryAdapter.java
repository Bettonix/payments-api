package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter: implementa a porta {@link PaymentRepository} usando Spring Data JPA.
 *
 * <p>Traduz entre o domínio (Payment) e a infraestrutura (PaymentEntity).
 * Camada application nunca importa essa classe — só a porta.</p>
 */
@Component
class JpaPaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository delegate;

    JpaPaymentRepositoryAdapter(SpringDataPaymentRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Payment save(Payment payment) {
        return delegate.save(PaymentEntity.fromDomain(payment)).toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return delegate.findById(id).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String key) {
        return delegate.findByIdempotencyKey(key).map(PaymentEntity::toDomain);
    }
}