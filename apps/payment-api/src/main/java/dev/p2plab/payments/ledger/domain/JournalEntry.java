package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An atomic, immutable group of postings that sums to zero: money moves, it is never created or destroyed.
 * If a JournalEntry object exists, it is balanced. V3__ledger.sql checks the same rule again at COMMIT.
 */
public record JournalEntry(UUID id, EntryKind kind, Instant createdAt, List<Posting> postings) {
    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two postings");
        }
        Set<UUID> accounts = new HashSet<>();
        Money total = Money.zero(postings.get(0).amount().currency());
        for (Posting posting : postings) {
            if (!accounts.add(posting.accountId())) {
                throw new IllegalArgumentException("an account may appear only once in a journal entry");
            }
            total = total.plus(posting.amount());
        }
        if (total.amountMinor() != 0) {
            throw new IllegalArgumentException("journal entry is unbalanced by " + total.amountMinor());
        }
    }

    /** The two-posting entry behind both funding and transfers: {@code amount} leaves {@code from} and arrives in {@code to}. */
    public static JournalEntry move(EntryKind kind, UUID from, UUID to, Money amount, Clock clock) {
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return new JournalEntry(UUID.randomUUID(), kind, clock.instant().truncatedTo(ChronoUnit.MICROS),
            List.of(new Posting(from, amount.negate()), new Posting(to, amount)));
    }
}
