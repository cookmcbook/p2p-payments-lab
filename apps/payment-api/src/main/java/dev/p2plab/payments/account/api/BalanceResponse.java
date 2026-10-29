package dev.p2plab.payments.account.api;

import dev.p2plab.payments.account.application.AccountBalance;
import java.util.UUID;

/** Amounts are integer minor units (cents) so JSON clients never parse money as floating point. */
public record BalanceResponse(UUID accountId, String currency, long ledgerMinor, long availableMinor) {
    static BalanceResponse from(AccountBalance balance) {
        return new BalanceResponse(
            balance.accountId(),
            balance.ledger().currency().name(),
            balance.ledger().amountMinor(),
            balance.available().amountMinor());
    }
}
