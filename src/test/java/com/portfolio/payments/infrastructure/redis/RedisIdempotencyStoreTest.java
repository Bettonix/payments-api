package com.portfolio.payments.infrastructure.redis;

import com.portfolio.payments.application.port.IdempotencyStore;
import com.portfolio.payments.application.port.IdempotencyStore.AcquireResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        store = new RedisIdempotencyStore(redisTemplate, registry);
    }

    @Test
    void tryAcquireReturnsAcquiredWhenKeyIsNew() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("fp123"), eq("120")))
            .thenReturn("ACQUIRED");

        AcquireResult result = store.tryAcquire("merchant-1", "key-1", "fp123", Duration.ofSeconds(120));

        assertInstanceOf(AcquireResult.Acquired.class, result);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("idemp:merchant-1:key-1")), eq("fp123"), eq("120"));
    }

    @Test
    void tryAcquireReturnsInProgressWhenKeyIsProcessing() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("fp123"), eq("120")))
            .thenReturn("IN_PROGRESS");

        AcquireResult result = store.tryAcquire("merchant-1", "key-1", "fp123", Duration.ofSeconds(120));

        assertInstanceOf(AcquireResult.InProgress.class, result);
    }

    @Test
    void tryAcquireReturnsPayloadMismatchWhenFingerprintDiffers() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("fp-diff"), eq("120")))
            .thenReturn("MISMATCH");

        AcquireResult result = store.tryAcquire("merchant-1", "key-1", "fp-diff", Duration.ofSeconds(120));

        assertInstanceOf(AcquireResult.PayloadMismatch.class, result);
    }

    @Test
    void tryAcquireReturnsCompletedWhenPaymentAlreadyFinished() {
        UUID paymentId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("fp123"), eq("120")))
            .thenReturn("COMPLETED:" + paymentId);

        AcquireResult result = store.tryAcquire("merchant-1", "key-1", "fp123", Duration.ofSeconds(120));

        assertInstanceOf(AcquireResult.Completed.class, result);
        assertEquals(paymentId, ((AcquireResult.Completed) result).paymentId());
    }

    @Test
    void tryAcquireFailsOpenWhenRedisThrowsException() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("Connection refused"));

        AcquireResult result = store.tryAcquire("merchant-1", "key-1", "fp123", Duration.ofSeconds(120));

        assertInstanceOf(AcquireResult.Bypassed.class, result, "Redis error must fail-open to DB uniqueness");
    }

    @Test
    void completeExecutesLuaScript() {
        UUID paymentId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq(paymentId.toString()), eq("fp123"), eq("86400")))
            .thenReturn("OK");

        store.complete("merchant-1", "key-1", paymentId, "fp123", Duration.ofSeconds(86400));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("idemp:merchant-1:key-1")),
            eq(paymentId.toString()), eq("fp123"), eq("86400"));
    }

    @Test
    void releaseDeletesKey() {
        when(redisTemplate.delete("idemp:merchant-1:key-1")).thenReturn(Boolean.TRUE);

        store.release("merchant-1", "key-1");

        verify(redisTemplate).delete("idemp:merchant-1:key-1");
    }
}
