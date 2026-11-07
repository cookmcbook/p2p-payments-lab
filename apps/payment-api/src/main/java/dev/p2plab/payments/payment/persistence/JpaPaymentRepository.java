package dev.p2plab.payments.payment.persistence;

import dev.p2plab.payments.payment.domain.Payment;
import dev.p2plab.payments.payment.domain.PaymentRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Adapts the {@link PaymentRepository} port to JPA. Callers must supply the transaction. */
@Repository
class JpaPaymentRepository implements PaymentRepository {
    private final EntityManager entityManager;

    JpaPaymentRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(Payment payment) {
        entityManager.persist(PaymentEntity.from(payment));
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(PaymentEntity.class, id)).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> lockById(UUID id) {
        // Written out rather than LockModeType.PESSIMISTIC_WRITE, so the SQL in the book is the SQL that runs.
        @SuppressWarnings("unchecked")
        List<PaymentEntity> rows = entityManager
            .createNativeQuery("SELECT * FROM payment WHERE id = :id FOR UPDATE", PaymentEntity.class)
            .setParameter("id", id)
            .getResultList();
        return rows.stream().findFirst().map(PaymentEntity::toDomain);
    }

    @Override
    public void update(Payment payment) {
        PaymentEntity entity = entityManager.find(PaymentEntity.class, payment.id());
        if (entity == null) {
            throw new IllegalStateException("payment " + payment.id() + " was never stored");
        }
        entity.apply(payment);
    }
}
