package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PreconditionFailedException;
import com.portfolio.payments.interfaces.rest.dto.CreatePaymentRequest;
import com.portfolio.payments.interfaces.rest.dto.FailPaymentRequest;
import com.portfolio.payments.interfaces.rest.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * API REST v1 de pagamentos seguindo padrão de mercado (estilo Stripe).
 */
@RestController
@RequestMapping({"/v1/payments", "/payments"})
@Tag(name = "Payments", description = "Operações de criação, consulta e transição de pagamentos")
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

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Criar ou reproduzir pagamento", description = "Garante idempotência estrita via header Idempotency-Key.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pagamento criado ou reproduzido com sucesso",
            headers = {
                @Header(name = "Location", description = "URI do recurso criado", schema = @Schema(type = "string")),
                @Header(name = "ETag", description = "Identificador de versão da entidade", schema = @Schema(type = "string")),
                @Header(name = "Idempotent-Replayed", description = "Presente quando a resposta foi recuperada de replay", schema = @Schema(type = "boolean"))
            },
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Parâmetros inválidos ou Idempotency-Key ausente",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Requisição idêntica já está em processamento concorrente",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "422", description = "Idempotency-Key reutilizada com payload divergente",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PaymentResponse> create(
        @Parameter(description = "Chave de idempotência única por requisição", required = true)
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
        @Valid @RequestBody CreatePaymentRequest request
    ) {
        Money amount = Money.of(request.amount(), request.currency());
        var result = createPayment.execute(idempotencyKey, request.payerId(), request.payeeId(), amount);
        Payment payment = result.payment();

        var body = PaymentResponse.fromDomain(payment);
        String etag = toETag(payment.version());

        var responseBuilder = ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/v1/payments/" + payment.id()))
            .eTag(etag);

        if (result instanceof CreatePaymentUseCase.Result.Replayed) {
            responseBuilder.header("Idempotent-Replayed", "true");
        }

        return responseBuilder.body(body);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Consultar pagamento por ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pagamento localizado",
            headers = @Header(name = "ETag", description = "Versão atual do recurso", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "404", description = "Pagamento não encontrado",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PaymentResponse> get(@PathVariable UUID id) {
        Payment payment = getPayment.byId(id);
        return ResponseEntity.ok()
            .eTag(toETag(payment.version()))
            .body(PaymentResponse.fromDomain(payment));
    }

    @PostMapping(value = "/{id}/authorize", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Autorizar pagamento")
    public ResponseEntity<PaymentResponse> authorize(
        @PathVariable UUID id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        return handleTransition(id, TransitionPaymentUseCase.Transition.AUTHORIZE, null, ifMatch);
    }

    @PostMapping(value = "/{id}/capture", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Capturar pagamento previamente autorizado")
    public ResponseEntity<PaymentResponse> capture(
        @PathVariable UUID id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        return handleTransition(id, TransitionPaymentUseCase.Transition.CAPTURE, null, ifMatch);
    }

    @PostMapping(value = "/{id}/settle", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Liquidar pagamento capturado (Operação Administrativa/PSP)")
    public ResponseEntity<PaymentResponse> settle(
        @PathVariable UUID id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        return handleTransition(id, TransitionPaymentUseCase.Transition.SETTLE, null, ifMatch);
    }

    @PostMapping(value = "/{id}/fail", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Marcar pagamento como falho com motivo")
    public ResponseEntity<PaymentResponse> fail(
        @PathVariable UUID id,
        @Valid @RequestBody FailPaymentRequest request,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        return handleTransition(id, TransitionPaymentUseCase.Transition.FAIL, request.reason(), ifMatch);
    }

    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancelar pagamento pendente")
    public ResponseEntity<PaymentResponse> cancel(
        @PathVariable UUID id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        return handleTransition(id, TransitionPaymentUseCase.Transition.CANCEL, null, ifMatch);
    }

    private ResponseEntity<PaymentResponse> handleTransition(
        UUID id,
        TransitionPaymentUseCase.Transition transition,
        String reason,
        String ifMatch
    ) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            Payment current = getPayment.byId(id);
            String currentETag = toETag(current.version());
            String expected = sanitizeETag(ifMatch);
            if (!sanitizeETag(currentETag).equals(expected)) {
                throw new PreconditionFailedException(ifMatch, currentETag);
            }
        }

        Payment updated = transitionPayment.execute(id, transition, reason);
        return ResponseEntity.ok()
            .eTag(toETag(updated.version()))
            .body(PaymentResponse.fromDomain(updated));
    }

    private String toETag(Long version) {
        return "\"v" + (version != null ? version : 0L) + "\"";
    }

    private String sanitizeETag(String etag) {
        if (etag == null) return "";
        return etag.trim().replace("\"", "").replace("W/", "");
    }
}