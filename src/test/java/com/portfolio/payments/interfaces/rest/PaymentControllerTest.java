package com.portfolio.payments.interfaces.rest;

import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes de integração da camada web — validam o contrato REST de verdade:
 * HTTP status codes, body JSON, headers, roteamento, error handlers.
 *
 * <p>Usa {@link WebMvcTest} que sobe só o controller + advice + Jackson,
 * mockando os use cases. Não precisa de Postgres, Redis ou Spring context
 * completo. Roda em ~1 segundo.</p>
 *
 * <p>Cobertura:</p>
 * <ul>
 *   <li>POST /payments 201 Created em caso de success</li>
 *   <li>POST /payments 200 OK em caso de replay</li>
 *   <li>POST /payments 409 Conflict em caso de race (idempotency conflict)</li>
 *   <li>POST /payments 400 Bad Request em caso de body inválido</li>
 *   <li>GET /payments/{id} 200 OK</li>
 *   <li>GET /payments/{id} 404 Not Found</li>
 *   <li>PATCH /payments/{id}/{transition} 200 OK</li>
 *   <li>PATCH /payments/{id}/invalid 400 Bad Request</li>
 *   <li>PATCH /payments/{id}/{transition} 409 Conflict (invalid transition)</li>
 * </ul>
 */
@WebMvcTest(PaymentController.class)
@Import(ApiExceptionHandler.class)
class PaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private CreatePaymentUseCase createUseCase;
    @MockBean private GetPaymentUseCase getUseCase;
    @MockBean private TransitionPaymentUseCase transitionUseCase;

    // ---------- POST /payments ----------

    @Test
    void createReturns201OnSuccess() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment payment = Payment.create("k1", payer, payee, Money.of(100, "BRL"));

        when(createUseCase.execute(eq("k1"), eq(payer), eq(payee), any(Money.class)))
            .thenReturn(CreatePaymentUseCase.Result.created(payment));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": "100.00",
              "currency": "BRL"
            }
            """.formatted(payer, payee);

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amount").value("100.00"))
            .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void createReturns200OnReplay() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment existing = Payment.create("k2", payer, payee, Money.of(50, "BRL"));

        when(createUseCase.execute(eq("k2"), any(), any(), any()))
            .thenReturn(CreatePaymentUseCase.Result.replayed(existing));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": "50.00",
              "currency": "BRL"
            }
            """.formatted(payer, payee);

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "k2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createReturns409OnIdempotencyRace() throws Exception {
        when(createUseCase.execute(any(), any(), any(), any()))
            .thenThrow(new IdempotencyKeyConflictException("k3"));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": "100.00",
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "k3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("idempotency_conflict"))
            .andExpect(jsonPath("$.idempotencyKey").value("k3"));
    }

    @Test
    void createReturns400OnMissingIdempotencyKey() throws Exception {
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": "100.00",
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns400OnInvalidBody() throws Exception {
        // amount negativo
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": "-100.00",
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "k4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void createReturns400OnInvalidCurrency() throws Exception {
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": "100.00",
              "currency": "DOLLAR"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "k5")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    // ---------- GET /payments/{id} ----------

    @Test
    void getReturns200() throws Exception {
        Payment p = Payment.create("k6", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        when(getUseCase.byId(p.id())).thenReturn(p);

        mockMvc.perform(get("/payments/{id}", p.id()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(p.id().toString()));
    }

    @Test
    void getReturns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(getUseCase.byId(id)).thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/payments/{id}", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("payment_not_found"));
    }

    // ---------- PATCH /payments/{id}/{transition} ----------

    @Test
    void patchAuthorizeReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        Payment p = Payment.create("k7", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        p.authorize();  // muta in-place pra AUTHORIZED

        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.AUTHORIZE), any()))
            .thenReturn(p);

        mockMvc.perform(patch("/payments/{id}/authorize", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void patchReturns400OnUnknownTransition() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/payments/{id}/explode", id))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_argument"));
    }

    @Test
    void patchReturns409OnInvalidTransition() throws Exception {
        UUID id = UUID.randomUUID();

        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.SETTLE), any()))
            .thenThrow(new InvalidPaymentTransitionException(
                id, PaymentStatus.PENDING, PaymentStatus.SETTLED));

        mockMvc.perform(patch("/payments/{id}/settle", id))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("invalid_transition"))
            .andExpect(jsonPath("$.from").value("PENDING"))
            .andExpect(jsonPath("$.to").value("SETTLED"));
    }

    @Test
    void patchReturns404WhenPaymentMissing() throws Exception {
        UUID id = UUID.randomUUID();

        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.AUTHORIZE), any()))
            .thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(patch("/payments/{id}/authorize", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("payment_not_found"));
    }
}
