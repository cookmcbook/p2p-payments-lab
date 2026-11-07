package dev.p2plab.payments.payment.domain;

import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to move money from a payer to a payee, and how far it has got. Immutable: each step returns a new
 * Payment, and refuses a move {@link PaymentStatus#canMoveTo} doesn't allow.
 */
public record Payment(
    UUID id,
    UUID payerId,
    UUID payeeId,
    Money amount,
    PaymentStatus status,
    RejectionReason rejectionReason,
    UUID holdId,
    UUID journalEntryId,
    Instant createdAt,
    Instant updatedAt) {

    public Payment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payerId, "payerId");
        Objects.requireNonNull(payeeId, "payeeId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (payerId.equals(payeeId)) {
            throw new IllegalArgumentException("payer and payee must be different accounts");
        }
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        // The same rules as V4's CHECK constraints, so a bad state fails here first, with a clear message.
        if ((status == PaymentStatus.REJECTED) != (rejectionReason != null)) {
            throw new IllegalArgumentException("a rejection reason is required exactly when the payment is REJECTED");
        }
        if ((status == PaymentStatus.COMPLETED) != (journalEntryId != null)) {
            throw new IllegalArgumentException("a journal entry is required exactly when the payment is COMPLETED");
        }
        if (status != PaymentStatus.REJECTED && holdId == null) {
            throw new IllegalArgumentException("only a REJECTED payment may have no hold");
        }
    }

    /** The payer's money is held: the payment starts its life ACCEPTED. */
    public static Payment accepted(UUID id, UUID payerId, UUID payeeId, Money amount, UUID holdId, Clock clock) {
        Instant now = now(clock);
        return new Payment(id, payerId, payeeId, amount, PaymentStatus.ACCEPTED, null, holdId, null, now, now);
    }

    /** Refused before anything was held. It is still recorded, so a retry with the same key gets the same answer. */
    public static Payment rejected(UUID id, UUID payerId, UUID payeeId, Money amount, RejectionReason reason, Clock clock) {
        Instant now = now(clock);
        return new Payment(id, payerId, payeeId, amount, PaymentStatus.REJECTED, reason, null, null, now, now);
    }

    public Payment startProcessing(Clock clock) {
        return moveTo(PaymentStatus.PROCESSING, null, journalEntryId, clock);
    }

    public Payment complete(UUID entryId, Clock clock) {
        return moveTo(PaymentStatus.COMPLETED, null, Objects.requireNonNull(entryId, "entryId"), clock);
    }

    private Payment moveTo(PaymentStatus next, RejectionReason reason, UUID entryId, Clock clock) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("payment " + id + " cannot move from " + status + " to " + next);
        }
        return new Payment(id, payerId, payeeId, amount, next, reason, holdId, entryId, createdAt, now(clock));
    }

    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
