package dev.p2plab.payments.payment.application;

import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.ledger.domain.JournalEntry;
import dev.p2plab.payments.payment.domain.IdempotencyKeyRepository;
import dev.p2plab.payments.payment.domain.IdempotencyKeyRepository.StoredKey;
import dev.p2plab.payments.payment.domain.Payment;
import dev.p2plab.payments.payment.domain.PaymentRepository;
import dev.p2plab.payments.payment.domain.PaymentStatus;
import dev.p2plab.payments.payment.domain.RejectionReason;
import dev.p2plab.payments.payment.domain.RequestFingerprint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three steps of a payment, each its own transaction, so each state is committed before the next step
 * starts. {@link PaymentService} calls them in order. They live in a separate bean because a call from inside the
 * same class would skip Spring's transaction proxy, and all three steps would silently share one transaction.
 */
@Service
public class PaymentSteps {
    private static final Logger log = LoggerFactory.getLogger(PaymentSteps.class);

    /** The result of step 1: the payment, and whether it already existed for this key. */
    public record Acceptance(Payment payment, boolean replayed) {
    }

    private final PaymentRepository payments;
    private final IdempotencyKeyRepository keys;
    private final LedgerService ledger;
    private final Clock clock;
    private final Duration keyRetention;

    public PaymentSteps(PaymentRepository payments, IdempotencyKeyRepository keys, LedgerService ledger, Clock clock,
                        @Value("${payments.idempotency.retention:PT24H}") Duration keyRetention) {
        this.payments = payments;
        this.keys = keys;
        this.ledger = ledger;
        this.clock = clock;
        this.keyRetention = keyRetention;
    }

    /**
     * Step 1: claim the idempotency key, then hold the payer's money (ACCEPTED) or record why not (REJECTED).
     * If the key is already taken, returns the payment it was first used for, or throws
     * {@link IdempotencyKeyReusedException} if that was a different request.
     */
    @Transactional
    public Acceptance accept(PaymentCommand command) {
        UUID payer = command.payerId();
        UUID payee = command.payeeId();
        checkParties(payer, payee);

        Instant now = clock.instant();
        String fingerprint = RequestFingerprint.of(payee, command.amount());
        keys.deleteIfExpired(payer, command.idempotencyKey(), now);
        UUID paymentId = UUID.randomUUID();
        if (!keys.claim(payer, command.idempotencyKey(), fingerprint, paymentId, now, now.plus(keyRetention))) {
            return replay(payer, command.idempotencyKey(), fingerprint);
        }

        Payment payment = ledger.placeHold(payer, command.amount())
            .map(hold -> Payment.accepted(paymentId, payer, payee, command.amount(), hold.id(), clock))
            .orElseGet(() -> Payment.rejected(paymentId, payer, payee, command.amount(), RejectionReason.INSUFFICIENT_FUNDS, clock));
        payments.add(payment);
        log.info("payment {} paymentId={} amountMinor={}", payment.status(), payment.id(), payment.amount().amountMinor());
        return new Acceptance(payment, false);
    }

    /** Step 2: mark that we have started moving the money. Does nothing unless the payment is ACCEPTED. */
    @Transactional
    public Payment startProcessing(UUID paymentId) {
        Payment payment = lock(paymentId);
        if (payment.status() != PaymentStatus.ACCEPTED) {
            return payment;
        }
        Payment processing = payment.startProcessing(clock);
        payments.update(processing);
        log.info("payment PROCESSING paymentId={}", paymentId);
        return processing;
    }

    /**
     * Step 3: capture the hold, which posts the journal entry, and mark the payment COMPLETED, in one transaction.
     * Both happen or neither does, so a PROCESSING payment found later has moved no money and can be resumed.
     * Does nothing unless the payment is PROCESSING.
     */
    @Transactional
    public Payment complete(UUID paymentId) {
        // The row lock makes a concurrent retry wait here, then see COMPLETED and do nothing.
        Payment payment = lock(paymentId);
        if (payment.status() != PaymentStatus.PROCESSING) {
            return payment;
        }
        JournalEntry entry = ledger.captureHold(payment.holdId(), payment.payeeId());
        Payment completed = payment.complete(entry.id(), clock);
        payments.update(completed);
        log.info("payment COMPLETED paymentId={} entryId={}", paymentId, entry.id());
        return completed;
    }

    private Acceptance replay(UUID payer, String key, String fingerprint) {
        StoredKey stored = keys.find(payer, key)
            .orElseThrow(() -> new IllegalStateException("idempotency key vanished between claim and read"));
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        Payment original = payments.findById(stored.paymentId())
            .orElseThrow(() -> new IllegalStateException("idempotency key points at a missing payment"));
        log.info("payment replayed paymentId={} status={}", original.id(), original.status());
        return new Acceptance(original, true);
    }

    private void checkParties(UUID payer, UUID payee) {
        if (payer.equals(payee)) {
            throw new IllegalArgumentException("payer and payee must be different accounts");
        }
        // Both must exist (404 otherwise), and neither may be the funding account: a payment from it would let
        // any caller mint simulated money, because it is allowed to go negative.
        if (ledger.account(payer).mayGoNegative() || ledger.account(payee).mayGoNegative()) {
            throw new IllegalArgumentException("payments are only between customer accounts");
        }
    }

    private Payment lock(UUID paymentId) {
        return payments.lockById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
