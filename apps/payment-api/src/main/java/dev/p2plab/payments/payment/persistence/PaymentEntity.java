package dev.p2plab.payments.payment.persistence;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import dev.p2plab.payments.payment.domain.Payment;
import dev.p2plab.payments.payment.domain.PaymentStatus;
import dev.p2plab.payments.payment.domain.RejectionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of {@code payment} (V4__payments.sql). Only status, entry and timestamps ever change. */
@Entity
@Table(name = "payment")
class PaymentEntity {
    @Id
    private UUID id;

    @Column(name = "payer_account_id", nullable = false, updatable = false)
    private UUID payerId;

    @Column(name = "payee_account_id", nullable = false, updatable = false)
    private UUID payeeId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 32)
    private RejectionReason rejectionReason;

    @Column(name = "hold_id", updatable = false)
    private UUID holdId;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {
    }

    static PaymentEntity from(Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.id = payment.id();
        entity.payerId = payment.payerId();
        entity.payeeId = payment.payeeId();
        entity.amountMinor = payment.amount().amountMinor();
        entity.currency = payment.amount().currency();
        entity.holdId = payment.holdId();
        entity.createdAt = payment.createdAt();
        entity.apply(payment);
        return entity;
    }

    /** Copies the fields that may change. Hibernate notices the change and writes an UPDATE at commit. */
    void apply(Payment payment) {
        if (!payment.id().equals(id)) {
            throw new IllegalArgumentException("not the same payment");
        }
        status = payment.status();
        rejectionReason = payment.rejectionReason();
        journalEntryId = payment.journalEntryId();
        updatedAt = payment.updatedAt();
    }

    Payment toDomain() {
        return new Payment(id, payerId, payeeId, new Money(amountMinor, currency), status, rejectionReason, holdId,
            journalEntryId, createdAt, updatedAt);
    }
}
