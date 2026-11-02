package dev.p2plab.payments.ledger.persistence;

import dev.p2plab.payments.ledger.domain.EntryKind;
import dev.p2plab.payments.ledger.domain.JournalEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of {@code journal_entry} (V3__ledger.sql). Insert-only: the table rejects UPDATE and DELETE. */
@Entity
@Table(name = "journal_entry")
class JournalEntryEntity {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EntryKind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JournalEntryEntity() {
    }

    static JournalEntryEntity from(JournalEntry entry) {
        JournalEntryEntity entity = new JournalEntryEntity();
        entity.id = entry.id();
        entity.kind = entry.kind();
        entity.createdAt = entry.createdAt();
        return entity;
    }
}
