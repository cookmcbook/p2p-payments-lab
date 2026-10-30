package dev.p2plab.payments.ledger.api;

import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lab-only door into the ledger, so the Chapter 3 experiments can move simulated money through the running
 * service. Exists only with the {@code lab} profile (infra/compose.lab.yaml). No authentication and no
 * idempotency: the real payment API, with both, is Chapter 4. See ADR 0005.
 */
@Profile("lab")
@RestController
@RequestMapping("/lab/ledger")
public class LabLedgerController {
    private static final Logger log = LoggerFactory.getLogger(LabLedgerController.class);

    private final LedgerService ledger;

    public LabLedgerController(LedgerService ledger) {
        this.ledger = ledger;
        log.warn("lab profile active: /lab/ledger endpoints move simulated money WITHOUT authentication; local use only");
    }

    @PostMapping("/funding")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalEntryResponse fund(@Valid @RequestBody FundingRequest request) {
        return JournalEntryResponse.from(ledger.fund(request.accountId(), money(request.amountMinor(), request.currency())));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalEntryResponse transfer(@Valid @RequestBody TransferRequest request) {
        return JournalEntryResponse.from(ledger.transfer(
            request.fromAccountId(), request.toAccountId(), money(request.amountMinor(), request.currency())));
    }

    private static Money money(long amountMinor, String currency) {
        return new Money(amountMinor, Currency.valueOf(currency));
    }
}
