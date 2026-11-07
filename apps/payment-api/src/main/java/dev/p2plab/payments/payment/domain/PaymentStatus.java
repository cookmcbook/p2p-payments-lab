package dev.p2plab.payments.payment.domain;

import java.util.Set;

/**
 * Where a payment is in its life. Every allowed move is listed here; anything else is a bug and throws.
 * V4__payments.sql's CHECK constraints make sure each stored state carries the facts it implies. See ADR 0008.
 */
public enum PaymentStatus {
    /** Recorded, and the payer's money is held. Nothing has moved yet. */
    ACCEPTED,
    /** We have started moving the money. For the internal ledger the move is one atomic transaction, so a
     *  PROCESSING payment found after a crash is safely resumed. */
    PROCESSING,
    /** The journal entry is posted. Final. */
    COMPLETED,
    /** Refused, with a reason. Nothing was held or moved. Final. */
    REJECTED,
    /** We asked an external system to move money and don't know whether it did. Only reconciliation may
     *  resolve it, never an automatic retry. No Chapter 4 path leads here; Chapter 7's bank adapter will. */
    INDETERMINATE;

    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED;
    }

    public boolean canMoveTo(PaymentStatus next) {
        return switch (this) {
            case ACCEPTED -> next == PROCESSING;
            case PROCESSING -> Set.of(COMPLETED, REJECTED, INDETERMINATE).contains(next);
            case INDETERMINATE -> next == COMPLETED || next == REJECTED;
            case COMPLETED, REJECTED -> false;
        };
    }
}
