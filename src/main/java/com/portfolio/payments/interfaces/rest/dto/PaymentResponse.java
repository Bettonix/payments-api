package com.portfolio.payments.interfaces.rest.dto;

import com.portfolio.payments.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública de um pagamento.
 */
@Schema(description = "Representação pública de um recurso de pagamento")
public record PaymentResponse(
    @Schema(description = "Identificador único do pagamento (UUID)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID id,

    @Schema(description = "Identificador do lojista (merchant)", example = "acme")
    String merchantId,

    @Schema(description = "Chave de idempotência enviada pelo cliente", example = "idem-key-12345")
    String idempotencyKey,

    @Schema(description = "Identificador do pagador (UUID)", example = "11111111-1111-1111-1111-111111111111")
    UUID payerId,

    @Schema(description = "Identificador do recebedor (UUID)", example = "22222222-2222-2222-2222-222222222222")
    UUID payeeId,

    @Schema(description = "Valor monetário do pagamento", example = "150.00")
    BigDecimal amount,

    @Schema(description = "Código de 3 letras da moeda (ISO 4217)", example = "BRL")
    String currency,

    @Schema(description = "Estado atual do pagamento no ciclo de vida", example = "PENDING")
    String status,

    @Schema(description = "Motivo da falha caso o status seja FAILED", example = "fraud_detection_trigger")
    String failureReason,

    @Schema(description = "Data e hora de criação em UTC (ISO-8601)", example = "2026-10-05T12:00:00Z")
    Instant createdAt,

    @Schema(description = "Data e hora da última atualização em UTC (ISO-8601)", example = "2026-10-05T12:00:00Z")
    Instant updatedAt,

    @Schema(description = "Versão do recurso (usada para ETag e controle de concorrência)", example = "1")
    Long version
) {
    public static PaymentResponse fromDomain(Payment p) {
        return new PaymentResponse(
            p.id(),
            p.merchantId(),
            p.idempotencyKey(),
            p.payerId(),
            p.payeeId(),
            p.amount().amount(),
            p.amount().currency().getCurrencyCode(),
            p.status().name(),
            p.failureReason(),
            p.createdAt(),
            p.updatedAt(),
            p.version() != null ? p.version() : 0L
        );
    }
}
