package dev.p2plab.payments.ledger.persistence;

import dev.p2plab.payments.ledger.domain.JournalEntry;
import dev.p2plab.payments.ledger.domain.LedgerAccount;
import dev.p2plab.payments.ledger.domain.LedgerRepository;
import dev.p2plab.payments.ledger.domain.Posting;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Adapts the domain {@link LedgerRepository} port to JPA and native SQL. Callers must supply the transaction. */
@Repository
class JpaLedgerRepository implements LedgerRepository {
    private final EntityManager entityManager;

    JpaLedgerRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LedgerAccount> find(UUID accountId) {
        return selectAccount("SELECT currency, kind FROM account WHERE id = :id", accountId);
    }

    @Override
    public Optional<LedgerAccount> lockForDebit(UUID accountId) {
        // FOR NO KEY UPDATE, not FOR UPDATE: it blocks other debits of this account, but not the FOR KEY SHARE
        // lock that a posting's foreign key takes when another transaction credits it. FOR UPDATE would make
        // opposite-direction transfers (A->B while B->A) deadlock. See ADR 0006.
        return selectAccount("SELECT currency, kind FROM account WHERE id = :id FOR NO KEY UPDATE", accountId);
    }

    @Override
    public Money balance(UUID accountId, Currency currency) {
        Object sum = entityManager
            .createNativeQuery("SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = :id")
            .setParameter("id", accountId)
            .getSingleResult();
        // PostgreSQL's SUM(bigint) is NUMERIC; longValueExact throws rather than silently truncating.
        return new Money(((BigDecimal) sum).longValueExact(), currency);
    }

    @Override
    public void append(JournalEntry entry) {
        // Persist order is insert order: the entry row must exist before postings reference it.
        entityManager.persist(JournalEntryEntity.from(entry));
        for (Posting posting : entry.postings()) {
            entityManager.persist(PostingEntity.from(entry.id(), posting));
        }
    }

    private Optional<LedgerAccount> selectAccount(String sql, UUID accountId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("id", accountId).getResultList();
        return rows.stream().findFirst().map(row -> new LedgerAccount(
            accountId, Currency.valueOf((String) row[0]), "FUNDING".equals(row[1])));
    }
}
