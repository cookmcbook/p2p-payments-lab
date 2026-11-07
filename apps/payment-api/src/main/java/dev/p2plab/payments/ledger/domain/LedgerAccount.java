package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.util.Objects;
import java.util.UUID;

/** The ledger's view of an account: just enough to decide whether a posting or a hold is allowed. */
public record LedgerAccount(UUID id, Currency currency, boolean mayGoNegative) {
    /** The seeded FUNDING account (V3__ledger.sql): the only place simulated money enters the ledger. */
    public static final UUID FUNDING_SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    public LedgerAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
    }

    public void checkCredit(Posting posting) {
        requireCurrency(posting.amount());
    }

    /**
     * Checks a debit against the account's available balance (ledger balance minus active holds). The caller
     * must hold a lock that stops other debits and holds on this account until it commits; otherwise
     * {@code available} can be stale (ADR 0006).
     */
    public void checkDebit(Money available, Posting posting) {
        if (!canSpend(available, posting.amount().negate())) {
            throw new InsufficientFundsException(id);
        }
    }

    /** Whether {@code amount} (positive) can be taken from {@code available}. Same locking rule as {@link #checkDebit}. */
    public boolean canSpend(Money available, Money amount) {
        requireCurrency(amount);
        requireCurrency(available);
        return mayGoNegative || !available.plus(amount.negate()).isNegative();
    }

    private void requireCurrency(Money amount) {
        if (amount.currency() != currency) {
            throw new IllegalArgumentException("currency " + amount.currency() + " does not match account " + id);
        }
    }
}
