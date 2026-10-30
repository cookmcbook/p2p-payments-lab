package dev.p2plab.payments.ledger.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** Amounts are integer minor units (cents). The cap keeps lab amounts far from {@code long} overflow. */
public record TransferRequest(
    @NotNull UUID fromAccountId,
    @NotNull UUID toAccountId,
    @Positive @Max(MAX_AMOUNT_MINOR) long amountMinor,
    @NotNull @Pattern(regexp = "USD", message = "only USD is supported") String currency) {

    /** $1,000,000.00 */
    static final long MAX_AMOUNT_MINOR = 100_000_000L;
}
