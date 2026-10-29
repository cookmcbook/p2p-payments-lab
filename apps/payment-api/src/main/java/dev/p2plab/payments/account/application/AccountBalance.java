package dev.p2plab.payments.account.application;

import dev.p2plab.payments.money.Money;
import java.util.UUID;

/**
 * Read model of an account's funds. {@code ledger} is the sum of posted entries; {@code available}
 * is what may be spent (ledger minus holds). Holds and postings do not exist until Chapter 3.
 */
public record AccountBalance(UUID accountId, Money ledger, Money available) {
}
