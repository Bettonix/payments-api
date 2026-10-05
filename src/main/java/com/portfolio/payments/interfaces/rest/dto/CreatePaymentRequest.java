package com.portfolio.payments.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Requisição para criação de uma intenção de pagamento.
 */
@Schema(description = "Dados para criação de uma intenção de pagamento")
public record CreatePaymentRequest(
    @NotNull(message = "payerId is required")
    @Schema(description = "Identificador do pagador (UUID)", example = "11111111-1111-1111-1111-111111111111")
    UUID payerId,

    @NotNull(message = "payeeId is required")
    @Schema(description = "Identificador do recebedor (UUID)", example = "22222222-2222-2222-2222-222222222222")
    UUID payeeId,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "amount precision cannot exceed 15 integer digits and 4 fraction digits")
    @Schema(description = "Valor monetário do pagamento", example = "150.00")
    BigDecimal amount,

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a valid 3-letter ISO 4217 code")
    @Schema(description = "Código de 3 letras da moeda (ISO 4217)", example = "BRL")
    String currency
) {}
