package dev.p2plab.payments.account.persistence;

import dev.p2plab.payments.account.domain.Account;
import dev.p2plab.payments.account.domain.AccountRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Adapts the domain {@link AccountRepository} port to JPA. Callers must supply the transaction. */
@Repository
class JpaAccountRepository implements AccountRepository {
    private final EntityManager entityManager;

    JpaAccountRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(Account account) {
        // persist (not merge): the id is new, so Hibernate issues one INSERT without a preceding SELECT.
        entityManager.persist(AccountEntity.from(account));
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(AccountEntity.class, id)).map(AccountEntity::toDomain);
    }
}
