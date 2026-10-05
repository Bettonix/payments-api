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
import com.portfolio.payments.infrastructure.security.JwtCurrentMerchant;
import com.portfolio.payments.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes da camada web para a API REST v1 cobrindo validações de contrato,
 * OAuth2 scopes (401/403) e isolamento multi-tenant (BOLA).
 */
@WebMvcTest(PaymentController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, JwtCurrentMerchant.class})
class PaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private CreatePaymentUseCase createUseCase;
    @MockBean private GetPaymentUseCase getUseCase;
    @MockBean private TransitionPaymentUseCase transitionUseCase;

    private static final String MERCHANT = "acme";

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor writeJwt() {
        return jwt()
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:write"), new SimpleGrantedAuthority("SCOPE_payments:read"))
            .jwt(j -> j.claim("merchant_id", MERCHANT));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor readJwt() {
        return jwt()
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))
            .jwt(j -> j.claim("merchant_id", MERCHANT));
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt()
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:admin"))
            .jwt(j -> j.claim("merchant_id", "payments-ops"));
    }

    // ---------- SEGURANÇA: 401 & 403 ----------

    @Test
    void unauthenticatedRequestReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/v1/payments/" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"));
    }

    @Test
    void insufficientScopeReturns403ProblemDetail() throws Exception {
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 100.00,
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        // Tenta fazer POST com token que só tem scope payments:read
        mockMvc.perform(post("/v1/payments")
                .with(readJwt())
                .header("Idempotency-Key", "k-forbidden")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"));
    }

    // ---------- POST /v1/payments ----------

    @Test
    void createReturns202OnSuccess() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment payment = Payment.create(MERCHANT, "k1", null, payer, payee, Money.of(100, "BRL"));

        when(createUseCase.execute(eq(MERCHANT), eq("k1"), eq(payer), eq(payee), any(Money.class)))
            .thenReturn(CreatePaymentUseCase.Result.accepted(payment));

        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": 100.00,
              "currency": "BRL"
            }
            """.formatted(payer, payee);

        mockMvc.perform(post("/v1/payments")
                .with(writeJwt())
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(header().string(HttpHeaders.LOCATION, "/v1/payments/" + payment.id()))
            .andExpect(header().string("Preference-Applied", "respond-async"))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amount").value(100.00))
            .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void createReturns200WithIdempotentReplayedHeaderOnReplay() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Payment existing = Payment.create(MERCHANT, "k2", null, payer, payee, Money.of(50, "BRL"));

        when(createUseCase.execute(eq(MERCHANT), eq("k2"), any(), any(), any()))
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
                .with(writeJwt())
                .header("Idempotency-Key", "k2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createReturns409WithRetryAfterOnIdempotencyRace() throws Exception {
        when(createUseCase.execute(any(), any(), any(), any(), any()))
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
                .with(writeJwt())
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
        when(createUseCase.execute(any(), any(), any(), any(), any()))
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
                .with(writeJwt())
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
                .with(writeJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem-type:idempotency-key-missing"));
    }

    @Test
    void createReturns400OnInvalidBody() throws Exception {
        String body = """
            {
              "payerId": "%s",
              "payeeId": "%s",
              "amount": -100.00,
              "currency": "BRL"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/payments")
                .with(writeJwt())
                .header("Idempotency-Key", "k4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"));
    }

    // ---------- GET /v1/payments/{id} & MULTI-TENANT ISOLATION ----------

    @Test
    void getReturns200WithETagForOwningMerchant() throws Exception {
        Payment p = Payment.create(MERCHANT, "k6", null, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        when(getUseCase.byId(p.id())).thenReturn(p);

        mockMvc.perform(get("/v1/payments/{id}", p.id())
                .with(readJwt()))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(jsonPath("$.id").value(p.id().toString()))
            .andExpect(jsonPath("$.merchantId").value(MERCHANT));
    }

    @Test
    void getReturns404WhenPaymentBelongsToAnotherMerchant() throws Exception {
        // Pagamento pertence ao lojista 'globex'
        Payment globexPayment = Payment.create("globex", "k-globex", null, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        when(getUseCase.byId(globexPayment.id())).thenReturn(globexPayment);

        // Requisitante é o lojista 'acme'
        mockMvc.perform(get("/v1/payments/{id}", globexPayment.id())
                .with(readJwt())) // readJwt usa merchant_id = acme
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:problem-type:payment-not-found"));
    }

    @Test
    void legacyUnversionedEndpointReturnsNotFound() throws Exception {
        mockMvc.perform(get("/payments/" + UUID.randomUUID())
                .with(readJwt()))
            .andExpect(status().isNotFound());
    }

    // ---------- TRANSITIONS & ROLES ----------

    @Test
    void postAuthorizeReturns200WithUpdatedETag() throws Exception {
        UUID id = UUID.randomUUID();
        Payment p = Payment.create(MERCHANT, "k7", null, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        p.authorize();

        when(getUseCase.byId(id)).thenReturn(p);
        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.AUTHORIZE), any()))
            .thenReturn(p);

        mockMvc.perform(post("/v1/payments/{id}/authorize", id)
                .with(writeJwt()))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void postTransitionReturns412WhenIfMatchFails() throws Exception {
        UUID id = UUID.randomUUID();
        Payment p = Payment.create(MERCHANT, "k8", null, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        when(getUseCase.byId(id)).thenReturn(p);

        mockMvc.perform(post("/v1/payments/{id}/authorize", id)
                .with(writeJwt())
                .header(HttpHeaders.IF_MATCH, "\"v999\""))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.type").value("urn:problem-type:precondition-failed"));
    }

    @Test
    void postSettleAllowedForAdminRole() throws Exception {
        UUID id = UUID.randomUUID();
        Payment p = Payment.create("payments-ops", "k9", null, UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));
        p.authorize();
        p.capture();
        p.settle();

        when(getUseCase.byId(id)).thenReturn(p);
        when(transitionUseCase.execute(eq(id), eq(TransitionPaymentUseCase.Transition.SETTLE), any()))
            .thenReturn(p);

        mockMvc.perform(post("/v1/payments/{id}/settle", id)
                .with(adminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SETTLED"));
    }
}
