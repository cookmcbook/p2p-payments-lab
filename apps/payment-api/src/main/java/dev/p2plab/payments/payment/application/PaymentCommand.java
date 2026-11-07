package dev.p2plab.payments.payment.application;

import dev.p2plab.payments.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What a caller asked for. {@code payerId} comes from the X-Payer-Account-Id header until Chapter 5 replaces it
 * with the authenticated user; {@code idempotencyKey} is scoped to that payer.
 */
public record PaymentCommand(UUID payerId, String idempotencyKey, UUID payeeId, Money amount) {
    public PaymentCommand {
        Objects.requireNonNull(payerId, "payerId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(payeeId, "payeeId");
        Objects.requireNonNull(amount, "amount");
    }
}
