package dev.p2plab.payments.payment.application;

import dev.p2plab.payments.payment.domain.IdempotencyKeyRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention policy, part 2 (ADR 0009): deletes expired idempotency keys in the background, so the table doesn't
 * grow forever. Correctness doesn't depend on it: {@link PaymentSteps#accept} ignores an expired key anyway.
 * The payments the keys pointed at are kept.
 */
@Component
public class IdempotencyKeyPurger {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurger.class);

    private final IdempotencyKeyRepository keys;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public IdempotencyKeyPurger(IdempotencyKeyRepository keys, PlatformTransactionManager transactionManager, Clock clock) {
        this.keys = keys;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${payments.idempotency.purge-interval:PT1H}",
               fixedDelayString = "${payments.idempotency.purge-interval:PT1H}")
    public int purge() {
        Integer purged = transaction.execute(status -> keys.purgeExpired(clock.instant()));
        log.info("expired idempotency keys purged count={}", purged);
        return purged == null ? 0 : purged;
    }
}
