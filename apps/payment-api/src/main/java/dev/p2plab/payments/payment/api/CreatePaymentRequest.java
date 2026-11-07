package dev.p2plab.payments.payment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/payments}. The payer is not in the body: it comes from a header now and from the
 * authenticated user in Chapter 5. Amounts are integer minor units (cents).
 */
public record CreatePaymentRequest(
    @NotNull UUID payeeAccountId,
    @Positive @Max(MAX_AMOUNT_MINOR) long amountMinor,
    @NotNull @Pattern(regexp = "USD", message = "only USD is supported") String currency) {

    /** $1,000,000.00, which keeps amounts far from {@code long} overflow. */
    static final long MAX_AMOUNT_MINOR = 100_000_000L;
}
