package dev.p2plab.payments.ledger.application;

import dev.p2plab.payments.ledger.domain.EntryKind;
import dev.p2plab.payments.ledger.domain.JournalEntry;
import dev.p2plab.payments.ledger.domain.LedgerAccount;
import dev.p2plab.payments.ledger.domain.LedgerRepository;
import dev.p2plab.payments.ledger.domain.Posting;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.util.Comparator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts balanced journal entries atomically and computes balances from them. */
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
                // Read the balance only AFTER taking the lock: every earlier debit of this account has committed
                // by now, and this statement's fresh snapshot includes it. Check-then-insert is safe only here.
                account.checkDebit(ledger.balance(id, account.currency()), posting);
            } else {
                ledger.find(id).orElseThrow(() -> new UnknownLedgerAccountException(id)).checkCredit(posting);
            }
        });
        ledger.append(entry);
        log.info("journal entry posted entryId={} kind={} postings={}", entry.id(), entry.kind(), entry.postings().size());
        return entry;
    }
}
