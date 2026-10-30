package dev.p2plab.payments.ledger.api;

import dev.p2plab.payments.ledger.domain.JournalEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A posted entry. Postings are signed integer minor units: negative left the account, positive arrived. */
public record JournalEntryResponse(UUID id, String kind, Instant createdAt, List<PostingResponse> postings) {
    public record PostingResponse(UUID accountId, long amountMinor, String currency) {
    }

    static JournalEntryResponse from(JournalEntry entry) {
        return new JournalEntryResponse(entry.id(), entry.kind().name(), entry.createdAt(), entry.postings().stream()
            .map(p -> new PostingResponse(p.accountId(), p.amount().amountMinor(), p.amount().currency().name()))
            .toList());
    }
}
