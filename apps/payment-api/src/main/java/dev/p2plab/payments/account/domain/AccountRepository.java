package dev.p2plab.payments.account.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for accounts. Implemented in the persistence package so domain code has no JPA dependency. */
public interface AccountRepository {
    void add(Account account);

    Optional<Account> findById(UUID id);
}
