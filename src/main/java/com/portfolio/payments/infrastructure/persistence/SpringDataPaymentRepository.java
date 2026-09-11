package com.portfolio.payments.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository — só funciona com a entity {@link PaymentEntity}.
 * Adapter que implementa a porta do domínio fica em {@link JpaPaymentRepositoryAdapter}.
 */
public interface SpringDataPaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    @Query("SELECT p FROM PaymentEntity p WHERE p.idempotencyKey = :key")
    Optional<PaymentEntity> findByIdempotencyKey(@Param("key") String key);
}