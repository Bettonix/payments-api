package com.portfolio.payments.application.port;

import java.time.Duration;
import java.util.UUID;

/**
 * Porta de persistência de idempotência distribuída.
 *
 * <p>Permite lock distribuído de curta duração durante o processamento (in-flight)
 * e armazenamento de longa duração com TTL após a conclusão, associando a chave
 * ao fingerprint do payload e ao identificador do pagamento gerado.</p>
 */
public interface IdempotencyStore {

    Duration DEFAULT_IN_FLIGHT_TTL = Duration.ofSeconds(120);
    Duration DEFAULT_COMPLETED_TTL = Duration.ofHours(24);

    sealed interface AcquireResult {
        record Acquired() implements AcquireResult {}
        record InProgress() implements AcquireResult {}
        record Completed(UUID paymentId) implements AcquireResult {}
        record PayloadMismatch() implements AcquireResult {}
        record Bypassed() implements AcquireResult {}

        static AcquireResult acquired() { return new Acquired(); }
        static AcquireResult inProgress() { return new InProgress(); }
        static AcquireResult completed(UUID paymentId) { return new Completed(paymentId); }
        static AcquireResult payloadMismatch() { return new PayloadMismatch(); }
        static AcquireResult bypassed() { return new Bypassed(); }
    }

    /**
     * Tenta adquirir o lock de idempotência para o par (merchantId, idempotencyKey).
     *
     * @param merchantId     ID do merchant autenticado
     * @param idempotencyKey Chave enviada no cabeçalho Idempotency-Key
     * @param fingerprint    Hash SHA-256 do payload canônico da requisição
     * @param inFlightTtl    Tempo máximo de vida enquanto em processamento
     * @return {@link AcquireResult} indicando o estado do registro
     */
    AcquireResult tryAcquire(String merchantId, String idempotencyKey, String fingerprint, Duration inFlightTtl);

    default AcquireResult tryAcquire(String merchantId, String idempotencyKey, String fingerprint) {
        return tryAcquire(merchantId, idempotencyKey, fingerprint, DEFAULT_IN_FLIGHT_TTL);
    }

    /**
     * Marca a chave como concluída, associando o ID do pagamento gerado e definindo TTL final.
     *
     * @param merchantId     ID do merchant
     * @param idempotencyKey Chave de idempotência
     * @param paymentId      ID do pagamento criado
     * @param fingerprint    Hash do payload para validação futura de reuso
     * @param completedTtl   TTL após conclusão (ex: 24h)
     */
    void complete(String merchantId, String idempotencyKey, UUID paymentId, String fingerprint, Duration completedTtl);

    default void complete(String merchantId, String idempotencyKey, UUID paymentId, String fingerprint) {
        complete(merchantId, idempotencyKey, paymentId, fingerprint, DEFAULT_COMPLETED_TTL);
    }

    /**
     * Libera a chave em caso de erro transitório ou falha antes de persistir no banco.
     *
     * @param merchantId     ID do merchant
     * @param idempotencyKey Chave a liberar
     */
    void release(String merchantId, String idempotencyKey);

    /**
     * Retorna uma implementação NoOp que sempre repassa para o banco de dados (fail-open).
     */
    static IdempotencyStore noop() {
        return new IdempotencyStore() {
            @Override
            public AcquireResult tryAcquire(String merchantId, String idempotencyKey, String fingerprint, Duration inFlightTtl) {
                return AcquireResult.bypassed();
            }

            @Override
            public void complete(String merchantId, String idempotencyKey, UUID paymentId, String fingerprint, Duration completedTtl) {
                // no-op
            }

            @Override
            public void release(String merchantId, String idempotencyKey) {
                // no-op
            }
        };
    }
}
