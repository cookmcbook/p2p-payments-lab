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
}
