package com.portfolio.payments.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload para transição de falha de pagamento.
 */
@Schema(description = "Dados para falha de pagamento")
public record FailPaymentRequest(
    @NotBlank(message = "reason is required")
    @Size(max = 500, message = "reason cannot exceed 500 characters")
    @Schema(description = "Motivo da falha ou recusa", example = "fraud_detection_trigger")
    String reason
) {}
