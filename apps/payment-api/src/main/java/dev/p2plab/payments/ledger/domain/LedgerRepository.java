package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the ledger. Every method must run inside the caller's transaction. */
public interface LedgerRepository {
    Optional<LedgerAccount> find(UUID accountId);

    /** Like {@link #find}, but also locks the account against other debits until this transaction ends. */
    Optional<LedgerAccount> lockForDebit(UUID accountId);

    /** Sum of every committed posting for the account (plus this transaction's own). */
    Money balance(UUID accountId, Currency currency);

    void append(JournalEntry entry);
}
