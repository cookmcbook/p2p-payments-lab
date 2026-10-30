package dev.p2plab.payments.account.application;

import dev.p2plab.payments.account.domain.Account;
import dev.p2plab.payments.account.domain.AccountRepository;
import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates account use cases: owns transaction boundaries, delegates rules to the domain. */
@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final Clock clock;

    public AccountService(AccountRepository accounts, LedgerService ledger, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public Account open(String displayName, Currency currency) {
        Account account = Account.open(displayName, currency, clock);
        accounts.add(account);
        // Log the id only; display names are user-supplied and treated as personal data.
        log.info("account opened accountId={} currency={}", account.id(), account.currency());
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public AccountBalance balance(UUID id) {
        Account account = get(id);
        // Derived from postings, never stored (ADR 0002). Nothing is held yet, so available equals ledger;
        // holds for in-flight payments arrive in Chapter 4.
        Money ledgerBalance = ledger.balance(account.id(), account.currency());
        return new AccountBalance(account.id(), ledgerBalance, ledgerBalance);
    }
}
