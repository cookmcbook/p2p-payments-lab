package dev.p2plab.payments.account.persistence;

import dev.p2plab.payments.account.domain.Account;
import dev.p2plab.payments.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of the {@code account} table created by V2__accounts.sql. */
@Entity
@Table(name = "account")
class AccountEntity {
    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    static AccountEntity from(Account account) {
        AccountEntity entity = new AccountEntity();
        entity.id = account.id();
        entity.displayName = account.displayName();
        entity.currency = account.currency();
        entity.createdAt = account.createdAt();
        return entity;
    }

    Account toDomain() {
        return new Account(id, displayName, currency, createdAt);
    }
}
