package dev.p2plab.payments.ledger.application;

import dev.p2plab.payments.ledger.domain.EntryKind;
import dev.p2plab.payments.ledger.domain.Hold;
import dev.p2plab.payments.ledger.domain.JournalEntry;
import dev.p2plab.payments.ledger.domain.LedgerAccount;
import dev.p2plab.payments.ledger.domain.LedgerRepository;
import dev.p2plab.payments.ledger.domain.Posting;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts balanced journal entries atomically, reserves money with holds, and computes balances. */
@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository ledger;
    private final Clock clock;

    public LedgerService(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public JournalEntry fund(UUID accountId, Money amount) {
        return post(JournalEntry.move(EntryKind.FUNDING, LedgerAccount.FUNDING_SOURCE_ID, accountId, amount, clock));
    }

    /**
     * Reserves {@code amount} on the account, or returns empty if its available balance can't cover it.
     * Insufficient funds is an expected outcome here, not an error: it returns instead of throwing, because an
     * exception leaving this proxied method would mark the caller's whole transaction rollback-only.
     */
    @Transactional
    public Optional<Hold> placeHold(UUID accountId, Money amount) {
        // The same lock as a debit: a hold spends available money, so it races exactly like a debit (ADR 0006).
        LedgerAccount account = ledger.lockForDebit(accountId).orElseThrow(() -> new UnknownLedgerAccountException(accountId));
        if (!account.canSpend(available(account), amount)) {
            return Optional.empty();
        }
        Hold hold = Hold.place(accountId, amount, clock);
        ledger.addHold(hold);
        log.info("hold placed holdId={} amountMinor={}", hold.id(), amount.amountMinor());
        return Optional.of(hold);
    }

    /**
     * Posts the entry that moves a held amount to {@code toAccountId}, and closes the hold, in one transaction.
     * Throws {@link IllegalStateException} if the hold is unknown or was already captured.
     */
    @Transactional
    public JournalEntry captureHold(UUID holdId, UUID toAccountId) {
        // Pre-generate the entry id so the hold can point at it before the entry's rows are flushed.
        UUID entryId = UUID.randomUUID();
        Hold hold = ledger.captureHold(holdId, entryId, clock.instant())
            .orElseThrow(() -> new IllegalStateException("hold " + holdId + " is not active"));
        JournalEntry move = JournalEntry.move(EntryKind.PAYMENT, hold.accountId(), toAccountId, hold.amount(), clock);
        // The hold is CAPTURED now, so the debit check below no longer counts it: the money is not counted twice.
        return post(new JournalEntry(entryId, move.kind(), move.createdAt(), move.postings()));
    }

    /** The ledger's view of an account, without locking it. Throws {@link UnknownLedgerAccountException}. */
    @Transactional(readOnly = true)
    public LedgerAccount account(UUID accountId) {
        return ledger.find(accountId).orElseThrow(() -> new UnknownLedgerAccountException(accountId));
    }

    /** Ledger balance minus active holds: what the account can still spend. */
    @Transactional(readOnly = true)
    public Money available(UUID accountId, Currency currency) {
        return ledger.balance(accountId, currency).plus(ledger.held(accountId, currency).negate());
    }

    /** Moves money directly, with no hold and no payment record. Payments use {@link #placeHold} and {@link #captureHold}. */
    @Transactional
    public JournalEntry transfer(UUID fromAccountId, UUID toAccountId, Money amount) {
        return post(JournalEntry.move(EntryKind.TRANSFER, fromAccountId, toAccountId, amount, clock));
    }

    @Transactional(readOnly = true)
    public Money balance(UUID accountId, Currency currency) {
        return ledger.balance(accountId, currency);
    }

    private JournalEntry post(JournalEntry entry) {
        // Visit accounts in id order, so two entries that debit the same accounts always lock them in the same
        // order and can never wait for each other in a cycle (a deadlock).
        entry.postings().stream().sorted(Comparator.comparing(Posting::accountId)).forEach(posting -> {
            UUID id = posting.accountId();
            if (posting.isDebit()) {
                LedgerAccount account = ledger.lockForDebit(id).orElseThrow(() -> new UnknownLedgerAccountException(id));
                // Read the balance only AFTER taking the lock: every earlier debit and hold of this account has
                // committed by now, and this statement's fresh snapshot includes it. Check-then-insert is safe only here.
                account.checkDebit(available(account), posting);
            } else {
                ledger.find(id).orElseThrow(() -> new UnknownLedgerAccountException(id)).checkCredit(posting);
            }
        });
        ledger.append(entry);
        log.info("journal entry posted entryId={} kind={} postings={}", entry.id(), entry.kind(), entry.postings().size());
        return entry;
    }

    private Money available(LedgerAccount account) {
        return available(account.id(), account.currency());
    }
}
