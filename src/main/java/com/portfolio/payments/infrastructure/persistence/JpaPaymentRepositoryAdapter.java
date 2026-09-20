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
 * <p>Importante: o save precisa preservar o {@code @Version} da entity
 * carregada — caso contrário optimistic locking falha em updates. Estratégia:
 * se a entity já existe, carregamos ela primeiro e atualizamos os campos
 * mutáveis in-place (o version é gerenciado pelo JPA via @Version).</p>
 *
 * <p>Camada application nunca importa essa classe — só a porta.</p>
 */
@Component
class JpaPaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository delegate;

    JpaPaymentRepositoryAdapter(SpringDataPaymentRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        Optional<PaymentEntity> existing = delegate.findById(payment.id());
        PaymentEntity entity = existing.orElseGet(() -> PaymentEntity.fromDomain(payment));
        // update mutable fields in-place so JPA's @Version increments correctly
        entity.updateFrom(payment);
        return delegate.save(entity).toDomain();
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