package com.portfolio.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Value object imutável representando uma quantia monetária.
 *
 * <p>Decisões: usar {@link BigDecimal} (não double) + moeda explícita.
 * Equals/hashCode baseiam-se em valor + moeda — duas quantias em moedas
 * diferentes nunca são iguais.
 *
 * <p>C9 Fix: Rejeita escala maior que as casas decimais permitidas pela moeda
 * para evitar arredondamento silencioso.</p>
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount required");
        Objects.requireNonNull(currency, "currency required");
        int maxFractionDigits = currency.getDefaultFractionDigits();
        if (maxFractionDigits >= 0) {
            try {
                amount = amount.setScale(maxFractionDigits, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                    "amount scale " + amount.scale() + " exceeds currency "
                    + currency.getCurrencyCode() + " fraction digits (" + maxFractionDigits + ")");
            }
        }
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }
}