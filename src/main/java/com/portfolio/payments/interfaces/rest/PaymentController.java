package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * API REST de pagamentos.
 *
 * <p>Endpoint POST /payments aceita header obrigatório
 * {@code Idempotency-Key} — cliente pode retentar a vontade com a mesma
 * chave, sempre recebe o mesmo payment (idempotency-safe).</p>
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final CreatePaymentUseCase createPayment;

    public PaymentController(CreatePaymentUseCase createPayment) {
        this.createPayment = createPayment;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
        @Valid @RequestBody CreatePaymentRequest request
    ) {
        Money amount = Money.of(request.amount(), request.currency());
        var result = createPayment.execute(
            idempotencyKey, request.payerId(), request.payeeId(), amount);

        var body = PaymentResponse.fromDomain(result.payment());
        HttpStatus status = switch (result) {
            case CreatePaymentUseCase.Result.Created ignored -> HttpStatus.CREATED;
            case CreatePaymentUseCase.Result.Replayed ignored -> HttpStatus.OK;
        };
        return ResponseEntity.status(status)
            .location(URI.create("/payments/" + result.payment().id()))
            .body(body);
    }

    public record CreatePaymentRequest(
        @NotNull UUID payerId,
        @NotNull UUID payeeId,
        @NotNull @Positive String amount,
        @NotBlank @Size(min = 3, max = 3) String currency
    ) {}

    public record PaymentResponse(
        UUID id,
        String idempotencyKey,
        UUID payerId,
        UUID payeeId,
        String amount,
        String currency,
        String status,
        String createdAt
    ) {
        static PaymentResponse fromDomain(Payment p) {
            return new PaymentResponse(
                p.id(),
                p.idempotencyKey(),
                p.payerId(),
                p.payeeId(),
                p.amount().amount().toPlainString(),
                p.amount().currency().getCurrencyCode(),
                p.status().name(),
                p.createdAt().toString()
            );
        }
    }
}