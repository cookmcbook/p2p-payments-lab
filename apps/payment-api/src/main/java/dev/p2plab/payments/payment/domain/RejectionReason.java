package dev.p2plab.payments.payment.domain;

/** Why a payment was refused. Stored in {@code payment.rejection_reason} and returned to the caller. */
public enum RejectionReason {
    /** The payer's available balance (ledger balance minus holds) was less than the amount. */
    INSUFFICIENT_FUNDS
}
