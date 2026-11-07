package dev.p2plab.payments.payment.application;

import dev.p2plab.payments.payment.domain.Payment;
import dev.p2plab.payments.payment.domain.PaymentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a payment through its steps. Deliberately not {@code @Transactional} itself: each step commits on its own,
 * so the state a crash leaves behind is a real, resumable state (ADR 0008).
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** The payment as it stands after this request, and whether the request was a replay of an earlier one. */
    public record PaymentOutcome(Payment payment, boolean replayed) {
    }

    private final PaymentSteps steps;
    private final PaymentRepository payments;

    public PaymentService(PaymentSteps steps, PaymentRepository payments) {
        this.steps = steps;
        this.payments = payments;
    }

    public PaymentOutcome pay(PaymentCommand command) {
        PaymentSteps.Acceptance acceptance = steps.accept(command);
        Payment payment = acceptance.payment();
        // A replay of an unfinished payment resumes it: this is how a crash between steps gets repaired.
        if (!payment.status().isFinal()) {
            try {
                payment = steps.startProcessing(payment.id());
                payment = steps.complete(payment.id());
            } catch (RuntimeException e) {
                // The last committed state stands. The caller sees it (202) and retries with the same key.
                log.warn("payment not finished paymentId={} status={}; a retry with the same Idempotency-Key resumes it",
                    payment.id(), payment.status(), e);
            }
        }
        return new PaymentOutcome(payment, acceptance.replayed());
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
