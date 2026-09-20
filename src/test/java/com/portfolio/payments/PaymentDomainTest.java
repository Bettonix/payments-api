package com.portfolio.payments;

import com.portfolio.payments.domain.InvalidPaymentTransitionException;
import com.portfolio.payments.domain.Money;
import com.portfolio.payments.domain.Payment;
import com.portfolio.payments.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test do domínio puro — não sobe Spring, não precisa de Postgres.
 * Foco: invariantes do agregado e state machine.
 */
class PaymentDomainTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @Test
    void createsPaymentInPendingState() {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Money amount = Money.of(1500, "BRL");

        Payment p = Payment.create("idem-123", payer, payee, amount);

        assertNotNull(p.id());
        assertEquals("idem-123", p.idempotencyKey());
        assertEquals(payer, p.payerId());
        assertEquals(payee, p.payeeId());
        assertEquals(amount, p.amount());
        assertEquals(PaymentStatus.PENDING, p.status());
        assertNotNull(p.createdAt());
    }

    @Test
    void rejectsNegativeAmount() {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
            () -> Payment.create("k", payer, payee, Money.of(-1, "BRL")));
        assertThrows(IllegalArgumentException.class,
            () -> Payment.create("k", payer, payee, Money.of(0, "BRL")));
    }

    @Test
    void rejectsPayerEqualsPayee() {
        UUID same = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> Payment.create("k", same, same, Money.of(100, "BRL")));
    }

    @Test
    void stateMachineRejectsInvalidTransition() {
        Payment p = Payment.create("k",
            UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        p.authorize();
        assertEquals(PaymentStatus.AUTHORIZED, p.status());

        // cannot settle directly from authorized
        assertThrows(InvalidPaymentTransitionException.class, p::settle);

        p.capture();
        p.settle();
        assertEquals(PaymentStatus.SETTLED, p.status());

        // terminal state — no transitions out
        assertThrows(InvalidPaymentTransitionException.class, () -> p.fail("x"));
    }

    @Test
    void canFailFromAnyNonTerminalState() {
        Payment p = Payment.create("k",
            UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "BRL"));

        p.fail("rejected by fraud engine");
        assertEquals(PaymentStatus.FAILED, p.status());
        assertEquals("rejected by fraud engine", p.failureReason());
    }

    @Test
    void rejectsMixedCurrencyAddition() {
        Money brl = Money.of(100, "BRL");
        Money usd = Money.of(50, "USD");
        assertThrows(IllegalArgumentException.class, () -> brl.plus(usd));
    }
}