package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox relay — picks pending events from the DB and hands them to the
 * configured {@link OutboxPublisher}. Runs on a fixed schedule (default:
 * every 5 seconds). Single-instance deployment assumes; for multi-instance,
 * add a row-level lock or a leader-election sidecar (e.g. via ShedLock).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository repository;
    private final OutboxPublisher publisher;

    public OutboxRelay(OutboxRepository repository, OutboxPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:5000}")
    @Transactional
    public void drain() {
        List<OutboxEvent> pending = repository.fetchPendingBatch(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("relay draining {} pending events", pending.size());
        for (OutboxEvent event : pending) {
            try {
                OutboxPublisher.PublishResult result = publisher.publish(event);
                switch (result) {
                    case SUCCESS -> {
                        event.markPublished();
                        repository.save(event);
                    }
                    case RETRYABLE_FAILURE -> {
                        event.markFailed("retryable failure");
                        if (event.attemptCount() >= MAX_ATTEMPTS) {
                            event.markGiveUp("max attempts exceeded");
                        }
                        repository.save(event);
                    }
                    case GIVE_UP -> {
                        event.markGiveUp("publisher refused");
                        repository.save(event);
                    }
                }
            } catch (RuntimeException ex) {
                log.error("relay caught unexpected error for event {}", event.id(), ex);
                event.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (event.attemptCount() >= MAX_ATTEMPTS) {
                    event.markGiveUp("max attempts exceeded");
                }
                repository.save(event);
            }
        }
    }
}