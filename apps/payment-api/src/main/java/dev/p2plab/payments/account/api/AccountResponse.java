package dev.p2plab.payments.account.api;

import dev.p2plab.payments.account.domain.Account;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String displayName, String currency, Instant createdAt) {
    static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.displayName(), account.currency().name(), account.createdAt());
    }
}
