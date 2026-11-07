package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the ledger. Every method must run inside the caller's transaction. */
public interface LedgerRepository {
    Optional<LedgerAccount> find(UUID accountId);

    /** Like {@link #find}, but also locks the account against other debits until this transaction ends. */
    Optional<LedgerAccount> lockForDebit(UUID accountId);

    /** Sum of every committed posting for the account (plus this transaction's own). */
    Money balance(UUID accountId, Currency currency);

    /** Sum of the account's ACTIVE holds. Available = {@link #balance} minus this. */
    Money held(UUID accountId, Currency currency);

    void append(JournalEntry entry);

    void addHold(Hold hold);

    /**
     * Marks an ACTIVE hold CAPTURED by {@code entryId}, locking its row. Returns the hold, or empty if it is
     * unknown or no longer active (another transaction captured or released it first).
     */
    Optional<Hold> captureHold(UUID holdId, UUID entryId, Instant at);
}
