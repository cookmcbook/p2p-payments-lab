package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Money reserved on an account for an in-flight payment. While ACTIVE it lowers the available balance but not
 * the ledger balance; capturing it posts the journal entry that actually moves the money. See ADR 0008.
 */
public record Hold(UUID id, UUID accountId, Money amount, Instant createdAt) {
    public Hold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("a hold must be positive");
        }
    }

    public static Hold place(UUID accountId, Money amount, Clock clock) {
        return new Hold(UUID.randomUUID(), accountId, amount, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
