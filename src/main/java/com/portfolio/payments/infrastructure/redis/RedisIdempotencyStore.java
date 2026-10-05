package com.portfolio.payments.infrastructure.redis;

import com.portfolio.payments.application.port.IdempotencyStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador de infraestrutura para persistência de idempotência no Redis.
 *
 * <p>Utiliza scripts Lua para garantir atomicidade no check-and-set (CAS) do lock in-flight
 * e na verificação do fingerprint de requisições concorrentes.</p>
 *
 * <p>Protegido por Circuit Breaker do Resilience4j com comportamento fail-open:
 * se o Redis estiver indisponível ou abrir o circuito, delega para a restrição de
 * unicidade no PostgreSQL {@code UNIQUE (merchant_id, idempotency_key)}.</p>
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private static final String ACQUIRE_LUA =
        "local key = KEYS[1]\n" +
        "local fingerprint = ARGV[1]\n" +
        "local ttl = tonumber(ARGV[2])\n" +
        "\n" +
        "if redis.call('EXISTS', key) == 0 then\n" +
        "    redis.call('HSET', key, 'status', 'PROCESSING', 'fingerprint', fingerprint)\n" +
        "    redis.call('EXPIRE', key, ttl)\n" +
        "    return 'ACQUIRED'\n" +
        "end\n" +
        "\n" +
        "local existingFingerprint = redis.call('HGET', key, 'fingerprint')\n" +
        "if existingFingerprint ~= fingerprint then\n" +
        "    return 'MISMATCH'\n" +
        "end\n" +
        "\n" +
        "local status = redis.call('HGET', key, 'status')\n" +
        "if status == 'PROCESSING' then\n" +
        "    return 'IN_PROGRESS'\n" +
        "elseif status == 'COMPLETED' then\n" +
        "    local paymentId = redis.call('HGET', key, 'paymentId')\n" +
        "    return 'COMPLETED:' .. (paymentId or '')\n" +
        "end\n" +
        "\n" +
        "return 'UNKNOWN'\n";

    private static final String COMPLETE_LUA =
        "local key = KEYS[1]\n" +
        "local paymentId = ARGV[1]\n" +
        "local fingerprint = ARGV[2]\n" +
        "local ttl = tonumber(ARGV[3])\n" +
        "\n" +
        "redis.call('HSET', key, 'status', 'COMPLETED', 'paymentId', paymentId, 'fingerprint', fingerprint)\n" +
        "redis.call('EXPIRE', key, ttl)\n" +
        "return 'OK'\n";

    private final StringRedisTemplate redisTemplate;
    private final CircuitBreaker circuitBreaker;
    private final RedisScript<String> acquireScript;
    private final RedisScript<String> completeScript;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate,
                                 CircuitBreakerRegistry circuitBreakerRegistry) {
        this.redisTemplate = redisTemplate;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisIdempotency");
        this.acquireScript = new DefaultRedisScript<>(ACQUIRE_LUA, String.class);
        this.completeScript = new DefaultRedisScript<>(COMPLETE_LUA, String.class);
    }

    @Override
    public AcquireResult tryAcquire(String merchantId, String idempotencyKey, String fingerprint, Duration inFlightTtl) {
        try {
            return circuitBreaker.executeSupplier(() -> doTryAcquire(merchantId, idempotencyKey, fingerprint, inFlightTtl));
        } catch (Throwable t) {
            log.warn("Redis idempotency store unavailable (fallback to DB constraint): {}", t.getMessage());
            return AcquireResult.bypassed();
        }
    }

    private AcquireResult doTryAcquire(String merchantId, String idempotencyKey, String fingerprint, Duration inFlightTtl) {
        String key = keyFor(merchantId, idempotencyKey);
        List<String> keys = Collections.singletonList(key);
        String rawResult = redisTemplate.execute(
            acquireScript,
            keys,
            fingerprint,
            String.valueOf(inFlightTtl.toSeconds())
        );

        if (rawResult == null) {
            return AcquireResult.bypassed();
        }

        if ("ACQUIRED".equals(rawResult)) {
            return AcquireResult.acquired();
        }
        if ("IN_PROGRESS".equals(rawResult)) {
            return AcquireResult.inProgress();
        }
        if ("MISMATCH".equals(rawResult)) {
            return AcquireResult.payloadMismatch();
        }
        if (rawResult.startsWith("COMPLETED:")) {
            String rawId = rawResult.substring("COMPLETED:".length());
            try {
                UUID id = UUID.fromString(rawId);
                return AcquireResult.completed(id);
            } catch (IllegalArgumentException e) {
                log.warn("Corrupted paymentId in redis key {}: {}", key, rawId);
                return AcquireResult.bypassed();
            }
        }

        log.warn("Unexpected Lua response from redis: {}", rawResult);
        return AcquireResult.bypassed();
    }

    @Override
    public void complete(String merchantId, String idempotencyKey, UUID paymentId, String fingerprint, Duration completedTtl) {
        try {
            circuitBreaker.executeRunnable(() -> doComplete(merchantId, idempotencyKey, paymentId, fingerprint, completedTtl));
        } catch (Throwable t) {
            log.warn("Failed to complete idempotency key in Redis for merchant={} key={}: {}", merchantId, idempotencyKey, t.getMessage());
        }
    }

    private void doComplete(String merchantId, String idempotencyKey, UUID paymentId, String fingerprint, Duration completedTtl) {
        String key = keyFor(merchantId, idempotencyKey);
        redisTemplate.execute(
            completeScript,
            Collections.singletonList(key),
            paymentId.toString(),
            fingerprint,
            String.valueOf(completedTtl.toSeconds())
        );
    }

    @Override
    public void release(String merchantId, String idempotencyKey) {
        try {
            circuitBreaker.executeRunnable(() -> redisTemplate.delete(keyFor(merchantId, idempotencyKey)));
        } catch (Throwable t) {
            log.warn("Failed to release idempotency key in Redis for merchant={} key={}: {}", merchantId, idempotencyKey, t.getMessage());
        }
    }

    private String keyFor(String merchantId, String idempotencyKey) {
        return "idemp:" + merchantId + ":" + idempotencyKey;
    }
}
