package dev.p2plab.payments.payment.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for idempotency keys, scoped to a payer. Every method must run inside the caller's transaction. */
public interface IdempotencyKeyRepository {
    /** What a key was first used for. */
    record StoredKey(String fingerprint, UUID paymentId) {
    }

    /**
     * Records the key for {@code paymentId} if no row exists for (payer, key). If another transaction is inserting
     * the same key right now, this waits for it to finish. Returns false if the key was already taken.
     */
    boolean claim(UUID payerId, String key, String fingerprint, UUID paymentId, Instant createdAt, Instant expiresAt);

    Optional<StoredKey> find(UUID payerId, String key);

    /** Removes this one key if it expired before {@code now}, so it can be used again. */
    void deleteIfExpired(UUID payerId, String key, Instant now);

    /** Removes every key that expired before {@code now}. Returns how many. */
    int purgeExpired(Instant now);
}
