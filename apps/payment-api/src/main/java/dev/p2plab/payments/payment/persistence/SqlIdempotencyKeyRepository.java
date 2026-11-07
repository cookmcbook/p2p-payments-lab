package dev.p2plab.payments.payment.persistence;

import dev.p2plab.payments.payment.domain.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Native SQL for {@code idempotency_key} (V4__payments.sql). Callers must supply the transaction. */
@Repository
class SqlIdempotencyKeyRepository implements IdempotencyKeyRepository {
    private final EntityManager entityManager;

    SqlIdempotencyKeyRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean claim(UUID payerId, String key, String fingerprint, UUID paymentId, Instant createdAt, Instant expiresAt) {
        // ON CONFLICT DO NOTHING: if another transaction has inserted this (payer, key) but not committed yet,
        // PostgreSQL makes this INSERT wait on the primary key until it does. Then: 0 rows if it committed
        // (the key is taken), 1 row if it rolled back (the key is ours).
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO idempotency_key
                    (payer_account_id, idempotency_key, request_fingerprint, payment_id, created_at, expires_at)
                VALUES (:payer, :key, :fingerprint, :payment, :createdAt, :expiresAt)
                ON CONFLICT (payer_account_id, idempotency_key) DO NOTHING""")
            .setParameter("payer", payerId)
            .setParameter("key", key)
            .setParameter("fingerprint", fingerprint)
            .setParameter("payment", paymentId)
            .setParameter("createdAt", createdAt)
            .setParameter("expiresAt", expiresAt)
            .executeUpdate();
        return inserted == 1;
    }

    @Override
    public Optional<StoredKey> find(UUID payerId, String key) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT request_fingerprint, payment_id FROM idempotency_key
                WHERE payer_account_id = :payer AND idempotency_key = :key""")
            .setParameter("payer", payerId)
            .setParameter("key", key)
            .getResultList();
        return rows.stream().findFirst().map(row -> new StoredKey((String) row[0], (UUID) row[1]));
    }

    @Override
    public void deleteIfExpired(UUID payerId, String key, Instant now) {
        entityManager.createNativeQuery("""
                DELETE FROM idempotency_key
                WHERE payer_account_id = :payer AND idempotency_key = :key AND expires_at <= :now""")
            .setParameter("payer", payerId)
            .setParameter("key", key)
            .setParameter("now", now)
            .executeUpdate();
    }

    @Override
    public int purgeExpired(Instant now) {
        return entityManager.createNativeQuery("DELETE FROM idempotency_key WHERE expires_at <= :now")
            .setParameter("now", now)
            .executeUpdate();
    }
}
