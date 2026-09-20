package com.portfolio.payments.infrastructure.persistence;

import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
class JpaOutboxRepositoryAdapter implements OutboxRepository {

    private final SpringDataOutboxRepository delegate;

    JpaOutboxRepositoryAdapter(SpringDataOutboxRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional
    public OutboxEvent save(OutboxEvent event) {
        Optional<OutboxEventEntity> existing = delegate.findById(event.id());
        OutboxEventEntity row = existing.orElseGet(() -> OutboxEventEntity.fromDomain(event));
        row.updateFrom(event);
        return delegate.save(row).toDomain();
    }

    @Override
    @Transactional
    public List<OutboxEvent> fetchPendingBatch(int limit) {
        return delegate.fetchPendingForUpdate(limit).stream()
            .map(OutboxEventEntity::toDomain)
            .toList();
    }
}