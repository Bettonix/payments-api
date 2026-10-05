package com.portfolio.payments.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta (Hexagonal Architecture) para persistência do agregado Payment.
 * A implementação fica em {@code infrastructure.persistence}.
 *
 * <p>Repositório só expõe métodos de domínio, não de ORM — assim a
 * camada application fica isolada do JPA.</p>
 */
public interface PaymentRepository {
    Payment insert(Payment payment);
    Payment save(Payment payment);
    Optional<Payment> findById(UUID id);
    Optional<Payment> findByIdempotencyKey(String key);
    default Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String key) {
        return findByIdempotencyKey(key);
    }
}