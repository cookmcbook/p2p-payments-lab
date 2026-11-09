package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Money;
import java.util.Objects;
import java.util.UUID;

/** One line of a journal entry: a signed amount for one account. Negative takes money out, positive puts it in. */
public record Posting(UUID accountId, Money amount) {
    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.amountMinor() == 0) {
            throw new IllegalArgumentException("a posting must move a non-zero amount");
        }
    }

    public boolean isDebit() {
        return amount.isNegative();
    }
}
