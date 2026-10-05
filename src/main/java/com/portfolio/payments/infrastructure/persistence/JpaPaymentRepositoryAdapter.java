package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter: implementa a porta {@link PaymentRepository} usando Spring Data JPA.
 *
 * <p>C1 Fix: {@code insert()} executa {@code saveAndFlush()} explícito,
 * garantindo que a constraint de unicidade seja disparada imediatamente
 * na chamada de persistência.</p>
 */
@Component
class JpaPaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository delegate;

    JpaPaymentRepositoryAdapter(SpringDataPaymentRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional
    public Payment insert(Payment payment) {
        PaymentEntity entity = PaymentEntity.fromDomain(payment);
        return delegate.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        Optional<PaymentEntity> existing = delegate.findById(payment.id());
        PaymentEntity entity = existing.orElseGet(() -> PaymentEntity.fromDomain(payment));
        entity.updateFrom(payment);
        return delegate.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return delegate.findById(id).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String key) {
        return delegate.findByIdempotencyKey(key).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String key) {
        return delegate.findByMerchantIdAndIdempotencyKey(merchantId, key).map(PaymentEntity::toDomain);
    }
}