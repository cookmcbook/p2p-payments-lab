package dev.p2plab.payments.account.domain;

import dev.p2plab.payments.money.Currency;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** A holder of funds. Carries no balance field: balances are derived from the ledger (Chapter 3). */
public record Account(UUID id, String displayName, Currency currency, Instant createdAt) {
    public static final int MAX_DISPLAY_NAME_LENGTH = 80;

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.strip();
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
    }

    public static Account open(String displayName, Currency currency, Clock clock) {
        // PostgreSQL TIMESTAMPTZ stores microseconds; truncating here keeps the value we return on create
        // identical to the value later read back from the database.
        return new Account(UUID.randomUUID(), displayName, currency, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
