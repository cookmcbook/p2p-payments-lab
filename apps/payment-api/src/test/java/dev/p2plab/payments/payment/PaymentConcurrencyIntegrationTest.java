package dev.p2plab.payments.payment;

import dev.p2plab.payments.account.application.AccountService;
import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.ledger.domain.Hold;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import dev.p2plab.payments.payment.application.PaymentCommand;
import dev.p2plab.payments.payment.application.PaymentService;
import dev.p2plab.payments.payment.application.PaymentService.PaymentOutcome;
import dev.p2plab.payments.payment.domain.PaymentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Many payments at the same moment, against a real PostgreSQL. Requires a running Docker daemon. */
@Testcontainers
@SpringBootTest
class PaymentConcurrencyIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(PaymentConcurrencyIntegrationTest.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(50);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void twentySimultaneousRetriesWithOneKeyMakeOnePayment() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        PaymentCommand command = new PaymentCommand(alice, "double-click", bob, usd(300));

        List<PaymentOutcome> outcomes = race(20, i -> command);

        long fresh = outcomes.stream().filter(o -> !o.replayed()).count();
        log.info("20 simultaneous requests with one key: payments={} fresh={} replayed={} alice={}",
            outcomes.stream().map(o -> o.payment().id()).distinct().count(), fresh, 20 - fresh, balance(alice));
        assertThat(outcomes).extracting(o -> o.payment().id()).containsOnly(outcomes.get(0).payment().id());
        assertThat(fresh).isOne();
        assertThat(outcomes).extracting(o -> o.payment().status()).containsOnly(PaymentStatus.COMPLETED);
        assertThat(balance(alice)).isEqualTo(700);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE payer_account_id = ?", Integer.class, alice)).isOne();
    }

    @Test
    void fiftySimultaneousPaymentsNeverHoldMoreThanIsAvailable() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);

        List<PaymentOutcome> outcomes = race(50, i -> new PaymentCommand(alice, "p-" + i, bob, usd(100)));

        long completed = outcomes.stream().filter(o -> o.payment().status() == PaymentStatus.COMPLETED).count();
        long rejected = outcomes.stream().filter(o -> o.payment().status() == PaymentStatus.REJECTED).count();
        log.info("50 concurrent $1.00 payments from a $10.00 balance: completed={} rejected={} alice={} bob={}",
            completed, rejected, balance(alice), balance(bob));
        assertThat(completed).isEqualTo(10);
        assertThat(rejected).isEqualTo(40);
        assertThat(balance(alice)).isZero();
        assertThat(balance(bob)).isEqualTo(1000);
        assertThat(ledger.available(alice, Currency.USD).amountMinor()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_hold WHERE account_id = ? AND status = 'ACTIVE'",
            Integer.class, alice)).isZero();
    }

    @Test
    void aWaitingHoldReadsTheHoldCommittedWhileItWaited() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // T1 holds the whole balance, then keeps its transaction (and the account row lock) open until released.
        Future<?> first = pool.submit(() -> tx.execute(status -> {
            ledger.placeHold(alice, usd(1000)).orElseThrow();
            lockHeld.countDown();
            await(release);
            return null;
        }));
        assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

        // T2 asks for $1.00 and must wait at SELECT ... FOR NO KEY UPDATE.
        Future<Optional<Hold>> second = pool.submit(() -> ledger.placeHold(alice, usd(100)));
        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        Integer waiting = jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
        log.info("while T1 holds the lock: T2 done={} sessions waiting on a lock={}", second.isDone(), waiting);
        assertThat(waiting).isEqualTo(1);

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        // T2 wakes up, sees T1's committed hold (available = 0), and refuses.
        assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(ledger.available(alice, Currency.USD).amountMinor()).isZero();
        assertThat(balance(alice)).isEqualTo(1000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private interface CommandFactory {
        PaymentCommand make(int i);
    }

    /** Submits every payment, then releases them at the same instant so they really do overlap. */
    private List<PaymentOutcome> race(int n, CommandFactory commands) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PaymentCommand command = commands.make(i);
            Callable<PaymentOutcome> task = () -> {
                start.await();
                return payments.pay(command);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<PaymentOutcome> outcomes = new ArrayList<>();
        for (Future<PaymentOutcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private UUID funded(String name, long cents) {
        UUID id = accounts.open(name, Currency.USD).id();
        if (cents > 0) {
            ledger.fund(id, usd(cents));
        }
        return id;
    }

    private long balance(UUID id) {
        return ledger.balance(id, Currency.USD).amountMinor();
    }

    private static Money usd(long cents) {
        return new Money(cents, Currency.USD);
    }
}
