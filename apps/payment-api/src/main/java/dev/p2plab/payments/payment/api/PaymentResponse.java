package dev.p2plab.payments.payment.api;

import dev.p2plab.payments.payment.domain.Payment;
import java.time.Instant;
import java.util.UUID;

/** JSON shape of a payment. {@code rejectionReason} and {@code journalEntryId} are null unless the state implies them. */
public record PaymentResponse(
    UUID id,
    UUID payerAccountId,
    UUID payeeAccountId,
    long amountMinor,
    String currency,
    String status,
    String rejectionReason,
    UUID holdId,
    UUID journalEntryId,
    Instant createdAt,
    Instant updatedAt) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.id(), payment.payerId(), payment.payeeId(), payment.amount().amountMinor(),
            payment.amount().currency().name(), payment.status().name(),
            payment.rejectionReason() == null ? null : payment.rejectionReason().name(),
            payment.holdId(), payment.journalEntryId(), payment.createdAt(), payment.updatedAt());
    }
}
