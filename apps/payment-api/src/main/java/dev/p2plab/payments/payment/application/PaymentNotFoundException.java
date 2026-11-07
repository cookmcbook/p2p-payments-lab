package dev.p2plab.payments.payment.application;

import java.util.UUID;

/** No payment has this id. Mapped to 404. */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID id) {
        super("payment not found: " + id);
    }
}
