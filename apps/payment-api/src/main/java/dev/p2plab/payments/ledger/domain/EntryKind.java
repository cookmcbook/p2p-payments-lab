package dev.p2plab.payments.ledger.domain;

/** Why a journal entry exists. Stored in {@code journal_entry.kind}. */
public enum EntryKind {
    /** Moves simulated money from the funding account to a customer. The only way money enters. */
    FUNDING,
    /** Moves money between two accounts directly, with no payment record (Chapter 3 tests and tools). */
    TRANSFER,
    /** Moves money for a payment, by capturing the payment's hold (Chapter 4). */
    PAYMENT
}
