package dev.p2plab.payments.ledger.domain;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain invariants of journal entries and debits. No Spring, no database. */
class JournalEntryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC);
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static Money usd(long cents) {
        return new Money(cents, Currency.USD);
    }

    @Test
    void moveDebitsOneAccountAndCreditsTheOtherBySameAmount() {
        JournalEntry entry = JournalEntry.move(EntryKind.TRANSFER, A, B, usd(500), CLOCK);

        assertThat(entry.postings()).containsExactly(new Posting(A, usd(-500)), new Posting(B, usd(500)));
        assertThat(entry.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void rejectsUnbalancedEntry() {
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryKind.TRANSFER, CLOCK.instant(),
                List.of(new Posting(A, usd(-500)), new Posting(B, usd(499)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unbalanced by -1");
    }

    @Test
    void rejectsSinglePostingEvenIfItCouldNeverBalance() {
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryKind.TRANSFER, CLOCK.instant(),
                List.of(new Posting(A, usd(-500)))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSameAccountOnBothSides() {
        assertThatThrownBy(() -> JournalEntry.move(EntryKind.TRANSFER, A, A, usd(500), CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> JournalEntry.move(EntryKind.TRANSFER, A, B, usd(0), CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalEntry.move(EntryKind.TRANSFER, A, B, usd(-500), CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postingsCannotBeChangedAfterConstruction() {
        JournalEntry entry = JournalEntry.move(EntryKind.TRANSFER, A, B, usd(500), CLOCK);
        assertThatThrownBy(() -> entry.postings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void customerCannotBeDebitedBelowZero() {
        LedgerAccount customer = new LedgerAccount(A, Currency.USD, false);

        customer.checkDebit(usd(500), new Posting(A, usd(-500)));
        assertThatThrownBy(() -> customer.checkDebit(usd(499), new Posting(A, usd(-500))))
            .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void fundingSourceMayGoNegative() {
        LedgerAccount funding = new LedgerAccount(LedgerAccount.FUNDING_SOURCE_ID, Currency.USD, true);

        funding.checkDebit(usd(0), new Posting(LedgerAccount.FUNDING_SOURCE_ID, usd(-1_000_000)));
    }
}
