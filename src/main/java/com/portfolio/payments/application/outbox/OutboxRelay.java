package com.portfolio.payments.application.outbox;

import com.portfolio.payments.domain.OutboxEvent;
import com.portfolio.payments.domain.OutboxRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Outbox relay — picks pending events from the DB and hands them to the
 * configured {@link OutboxPublisher}.
 *
 * <p>C4 & C5 Fixes:
 * 1. I/O fora de transação de banco de dados (elimina locks prolongados).
 * 2. Lease curto com claim atômico via {@code claimBatch}.
 * 3. Integração com CircuitBreaker programático; falhas são devidamente
 * registradas no CB e quando o circuito abre os eventos recebem status
 * DEFERRED sem queimar tentativas.
 * 4. Backoff exponencial com jitter para retentativas.</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    public static final String OUTBOX_CB = "outboxPublisher";

    private final OutboxRepository repository;
    private final OutboxPublisher publisher;
    private final CircuitBreaker circuitBreaker;

    public OutboxRelay(OutboxRepository repository,
                       OutboxPublisher publisher,
                       CircuitBreakerRegistry circuitBreakerRegistry) {
        this.repository = repository;
        this.publisher = publisher;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(OUTBOX_CB);
    }

    public OutboxRelay(OutboxRepository repository, OutboxPublisher publisher) {
        this(repository, publisher, CircuitBreakerRegistry.ofDefaults());
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:5000}")
    public void drain() {
        List<OutboxEvent> pending = repository.claimBatch(BATCH_SIZE, LEASE_DURATION);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("relay claimed {} pending events", pending.size());

        Map<UUID, OutboxPublisher.PublishResult> results;
        try {
            // Executa a publicação protegida pelo Circuit Breaker
            results = circuitBreaker.executeSupplier(() -> publisher.publishBatch(pending));
        } catch (CallNotPermittedException e) {
            log.warn("outbox circuit breaker OPEN, skipping publish for {} events", pending.size());
            return;
        } catch (Exception ex) {
            log.error("unexpected error during batch publish", ex);
            for (OutboxEvent event : pending) {
                handleFailure(event, ex.getMessage());
            }
            return;
        }

        List<UUID> publishedIds = new ArrayList<>();
        for (OutboxEvent event : pending) {
            OutboxPublisher.PublishResult result = results.getOrDefault(event.id(), OutboxPublisher.PublishResult.RETRYABLE_FAILURE);
            switch (result) {
                case SUCCESS -> publishedIds.add(event.id());
                case RETRYABLE_FAILURE -> handleFailure(event, "retryable publisher failure");
                case GIVE_UP -> repository.markFailed(event.id(), event.attemptCount() + 1, "publisher rejected event permanently");
                case DEFERRED -> {
                    // Não consome tentativa, deixa para a próxima rodada
                    log.debug("event {} deferred", event.id());
                }
            }
        }

        if (!publishedIds.isEmpty()) {
            repository.markPublished(publishedIds, Instant.now());
            log.debug("marked {} events as PUBLISHED", publishedIds.size());
        }
    }

    private void handleFailure(OutboxEvent event, String error) {
        int nextAttempt = event.attemptCount() + 1;
        if (nextAttempt >= MAX_ATTEMPTS) {
            log.warn("event {} exceeded max attempts ({}), marking as FAILED", event.id(), MAX_ATTEMPTS);
            repository.markFailed(event.id(), nextAttempt, error);
        } else {
            Duration delay = calculateBackoff(nextAttempt);
            Instant nextAttemptAt = Instant.now().plus(delay);
            repository.scheduleRetry(event.id(), nextAttempt, nextAttemptAt, error);
        }
    }

    private Duration calculateBackoff(int attempt) {
        // Backoff exponencial com jitter: base 1s, max 5min
        long baseMillis = 1000L;
        long maxMillis = 300_000L;
        long exp = Math.min(maxMillis, baseMillis * (1L << Math.min(attempt, 8)));
        long jitter = ThreadLocalRandom.current().nextLong(exp / 2, exp);
        return Duration.ofMillis(jitter);
    }
}