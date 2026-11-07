package dev.p2plab.payments.payment.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for payments. Every method must run inside the caller's transaction. */
public interface PaymentRepository {
    void add(Payment payment);

    Optional<Payment> findById(UUID id);

    /** Like {@link #findById}, but locks the row until this transaction ends, so only one request advances it. */
    Optional<Payment> lockById(UUID id);

    /** Saves a new state of a payment previously loaded in this transaction with {@link #lockById}. */
    void update(Payment payment);
}
