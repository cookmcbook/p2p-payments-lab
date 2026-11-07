package dev.p2plab.payments.payment.application;

/** The payer used an idempotency key they had already used for a different request. Mapped to 422. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("idempotency key was already used with a different request");
    }
}
