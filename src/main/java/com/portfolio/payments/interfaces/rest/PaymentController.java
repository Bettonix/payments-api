package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * <p>Idempotency-safe via header {@code Idempotency-Key} no POST.
 * Transitions de estado controladas via PATCH (path segment = transition).</p>
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final CreatePaymentUseCase createPayment;
    private final GetPaymentUseCase getPayment;
    private final TransitionPaymentUseCase transitionPayment;

    public PaymentController(CreatePaymentUseCase createPayment,
                             GetPaymentUseCase getPayment,
                             TransitionPaymentUseCase transitionPayment) {
        this.createPayment = createPayment;
        this.getPayment = getPayment;
        this.transitionPayment = transitionPayment;
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

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.fromDomain(getPayment.byId(id));
    }

    @PatchMapping("/{id}/{transition}")
    public PaymentResponse transition(
        @PathVariable UUID id,
        @PathVariable String transition,
        @RequestBody(required = false) TransitionRequest body
    ) {
        TransitionPaymentUseCase.Transition op;
        try {
            op = TransitionPaymentUseCase.Transition.valueOf(transition.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "unknown transition: " + transition + " (allowed: authorize, capture, settle, fail, cancel)");
        }
        String reason = body != null ? body.reason() : null;
        Payment updated = transitionPayment.execute(id, op, reason);
        return PaymentResponse.fromDomain(updated);
    }

    public record CreatePaymentRequest(
        @NotNull UUID payerId,
        @NotNull UUID payeeId,
        @NotNull @Positive String amount,
        @NotBlank @Size(min = 3, max = 3) String currency
    ) {}

    public record TransitionRequest(
        @Size(max = 500) String reason
    ) {}

    public record PaymentResponse(
        UUID id,
        String idempotencyKey,
        UUID payerId,
        UUID payeeId,
        String amount,
        String currency,
        String status,
        String failureReason,
        String createdAt,
        String updatedAt
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
                p.failureReason(),
                p.createdAt().toString(),
                p.updatedAt().toString()
            );
        }
    }
}