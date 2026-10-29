package dev.p2plab.payments.account.application;

import dev.p2plab.payments.account.domain.Account;
import dev.p2plab.payments.account.domain.AccountRepository;
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
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
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
        // No ledger exists yet, so no funds can exist: every account is zero. Chapter 3 replaces this
        // with a sum over immutable journal postings.
        Money zero = Money.zero(account.currency());
        return new AccountBalance(account.id(), zero, zero);
    }
}
