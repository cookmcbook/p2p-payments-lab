package dev.p2plab.payments.money;

import java.util.Objects;

/** An amount in integer minor units (cents for USD). Never floating point; see ADR 0002. */
public record Money(long amountMinor, Currency currency) {
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /** Exact addition: overflow throws {@link ArithmeticException} instead of wrapping to a negative number. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    private void requireSameCurrency(Money other) {
        if (other.currency != currency) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
