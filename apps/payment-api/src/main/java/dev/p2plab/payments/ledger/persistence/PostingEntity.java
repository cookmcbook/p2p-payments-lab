package dev.p2plab.payments.ledger.persistence;

import dev.p2plab.payments.ledger.domain.Posting;
import dev.p2plab.payments.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** JPA mapping of {@code posting} (V3__ledger.sql). Insert-only: the table rejects UPDATE and DELETE. */
@Entity
@Table(name = "posting")
class PostingEntity {
    @Id
    private UUID id;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    protected PostingEntity() {
    }

    static PostingEntity from(UUID entryId, Posting posting) {
        PostingEntity entity = new PostingEntity();
        entity.id = UUID.randomUUID();
        entity.entryId = entryId;
        entity.accountId = posting.accountId();
        entity.amountMinor = posting.amount().amountMinor();
        entity.currency = posting.amount().currency();
        return entity;
    }
}
