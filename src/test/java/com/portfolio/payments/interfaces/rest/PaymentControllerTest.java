package com.portfolio.payments.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.payments.application.CreatePaymentUseCase;
import com.portfolio.payments.application.GetPaymentUseCase;
import com.portfolio.payments.application.TransitionPaymentUseCase;
import com.portfolio.payments.domain.IdempotencyKeyConflictException;
import com.portfolio.payments.domain.IdempotencyPayloadMismatchException;
import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentNotFoundException;
import com.portfolio.payments.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes da camada web para a API REST v1 seguindo padrão RFC 9457 e endpoints Stripe-style.
 */
@WebMvcTest(PaymentController.class)
@Import(ApiExceptionHandler.class)
class PaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private CreatePaymentUseCase createUseCase;
    @MockBean private GetPaymentUseCase getUseCase;
    @MockBean private TransitionPaymentUseCase transitionUseCase;

    // ---------- POST /v1/payments ----------

    @Test
    void createReturns201OnSuccess() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment payment = Payment.create("k1", payer, payee, Money.of(100, "BRL"));

        when(createUseCase.execute(eq("k1"), eq(payer), eq(payee), any(Money.class)))
            .thenReturn(CreatePaymentUseCase.Result.created(payment));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 100.00,
              "currency": "BRL"
            }
            """.formatted(payer, payee);

        mockMvc.perform(post("/v1/payments")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/v1/payments/" + payment.id()))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amount").value(100.00))
            .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void createReturns201WithIdempotentReplayedHeaderOnReplay() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment existing = Payment.create("k2", payer, payee, Money.of(50, "BRL"));

        when(createUseCase.execute(eq("k2"), any(), any(), any()))
            .thenReturn(CreatePaymentUseCase.Result.replayed(existing));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 50.00,
              "currency": "BRL"
            }
            """.formatted(payer, payee);

        mockMvc.perform(post("/v1/payments")
                .header("Idempotency-Key", "k2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createReturns409WithRetryAfterOnIdempotencyRace() throws Exception {
        when(createUseCase.execute(any(), any(), any(), any()))
            .thenThrow(new IdempotencyKeyConflictException("k3"));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 100.00,
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/payments")
                .header("Idempotency-Key", "k3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
            .andExpect(jsonPath("$.type").value("urn:problem-type:idempotency-request-in-progress"))
            .andExpect(jsonPath("$.idempotencyKey").value("k3"));
    }

    @Test
    void createReturns422WhenKeyReusedWithDifferentPayload() throws Exception {
        when(createUseCase.execute(any(), any(), any(), any()))
            .thenThrow(new IdempotencyPayloadMismatchException("k-reused"));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 999.00,
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/payments")
                .header("Idempotency-Key", "k-reused")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("urn:problem-type:idempotency-key-reused"))
            .andExpect(jsonPath("$.idempotencyKey").value("k-reused"));
    }

    @Test
    void createReturns400OnMissingIdempotencyKey() throws Exception {
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 100.00,
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem-type:idempotency-key-missing"));
    }

    @Test
    void createReturns400OnInvalidBody() throws Exception {
        // amount negativo
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": -100.00,
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/payments")
                .header("Idempotency-Key", "k4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"));
    }

    @Test
    void createReturns400OnInvalidCurrency() throws Exception {
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 100.00,
              "currency": "DOLLAR"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/payments")
                .header("Idempotency-Key", "k5")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"));
    }

    // ---------- GET /v1/payments/{id} ----------

    @Test
    void getReturns200WithETag() throws Exception {
        Payment p = Payment.create("k6", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        when(getUseCase.byId(p.id())).thenReturn(p);

        mockMvc.perform(get("/v1/payments/{id}", p.id()))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(jsonPath("$.id").value(p.id().toString()));
    }

    @Test
    void getReturns404ProblemDetailWhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(getUseCase.byId(id)).thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/v1/payments/{id}", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:problem-type:payment-not-found"));
    }

    // ---------- POST /v1/payments/{id}/authorize ----------

    @Test
    void postAuthorizeReturns200WithUpdatedETag() throws Exception {
        UUID id = UUID.randomUUID();
        Payment p = Payment.create("k7", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        p.authorize();

        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.AUTHORIZE), any()))
            .thenReturn(p);

        mockMvc.perform(post("/v1/payments/{id}/authorize", id))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void postTransitionReturns412WhenIfMatchFails() throws Exception {
        UUID id = UUID.randomUUID();
        Payment p = Payment.create("k8", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        when(getUseCase.byId(id)).thenReturn(p);

        mockMvc.perform(post("/v1/payments/{id}/authorize", id)
                .header(HttpHeaders.IF_MATCH, "\"v999\""))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.type").value("urn:problem-type:precondition-failed"));
    }

    @Test
    void postReturns409OnInvalidTransition() throws Exception {
        UUID id = UUID.randomUUID();

        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.SETTLE), any()))
            .thenThrow(new InvalidPaymentTransitionException(
                id, PaymentStatus.PENDING, PaymentStatus.SETTLED));

        mockMvc.perform(post("/v1/payments/{id}/settle", id))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:problem-type:invalid-state-transition"))
            .andExpect(jsonPath("$.from").value("PENDING"))
            .andExpect(jsonPath("$.to").value("SETTLED"));
    }

    @Test
    void postReturns404WhenPaymentMissing() throws Exception {
        UUID id = UUID.randomUUID();

        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.AUTHORIZE), any()))
            .thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(post("/v1/payments/{id}/authorize", id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:problem-type:payment-not-found"));
    }
}
