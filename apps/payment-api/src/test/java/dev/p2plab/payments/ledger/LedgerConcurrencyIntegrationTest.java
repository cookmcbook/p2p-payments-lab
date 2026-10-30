package dev.p2plab.payments.ledger;

import dev.p2plab.payments.account.application.AccountService;
import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.ledger.domain.InsufficientFundsException;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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

/**
 * Many transactions touching the same account at the same moment, against a real PostgreSQL. These tests
 * exercise ADR 0006: lock the debited account, then read its balance. Requires a running Docker daemon.
 */
@Testcontainers
@SpringBootTest
class LedgerConcurrencyIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(LedgerConcurrencyIntegrationTest.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(50);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private static Money usd(long cents) {
        return new Money(cents, Currency.USD);
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

    /** Submits every task, then releases them at the same instant so they really do overlap. */
    private List<Future<Object>> raceAll(List<Callable<Object>> tasks) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        return futures;
    }

    @Test
    void fiftySimultaneousDebitsNeverOverdraw() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> ledger.transfer(alice, bob, usd(100)));
        }

        int completed = 0;
        int insufficient = 0;
        for (Future<Object> f : raceAll(tasks)) {
            try {
                f.get(60, TimeUnit.SECONDS);
                completed++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(InsufficientFundsException.class);
                insufficient++;
            }
        }

        log.info("50 concurrent $1.00 transfers from a $10.00 balance: completed={} insufficient={} alice={} bob={}",
            completed, insufficient, balance(alice), balance(bob));
        assertThat(completed).isEqualTo(10);
        assertThat(insufficient).isEqualTo(40);
        assertThat(balance(alice)).isZero();
        assertThat(balance(bob)).isEqualTo(1000);
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 1000);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean aliceSends = i % 2 == 0;
            tasks.add(() -> aliceSends ? ledger.transfer(alice, bob, usd(10)) : ledger.transfer(bob, alice, usd(10)));
        }

        // With FOR UPDATE instead of FOR NO KEY UPDATE, PostgreSQL aborts some of these with "deadlock detected".
        for (Future<Object> f : raceAll(tasks)) {
            f.get(60, TimeUnit.SECONDS);
        }
        assertThat(balance(alice)).isEqualTo(1000);
        assertThat(balance(bob)).isEqualTo(1000);
    }

    @Test
    void aWaitingDebitReadsTheBalanceCommittedWhileItWaited() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // T1 spends the whole balance, then keeps its transaction (and the row lock) open until released.
        Future<?> first = pool.submit(() -> tx.execute(status -> {
            ledger.transfer(alice, bob, usd(1000));
            lockHeld.countDown();
            await(release);
            return null;
        }));
        lockHeld.await(10, TimeUnit.SECONDS);

        // T2 tries to spend $1.00 and must wait at SELECT ... FOR NO KEY UPDATE.
        Future<?> second = pool.submit(() -> ledger.transfer(alice, bob, usd(100)));
        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        Integer waiting = jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
        log.info("while T1 holds the lock: T2 done={} sessions waiting on a lock={}", second.isDone(), waiting);
        assertThat(waiting).isEqualTo(1);

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        // T2 wakes up, reads the balance T1 committed (0), and refuses.
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(InsufficientFundsException.class);
        assertThat(balance(alice)).isZero();
    }

    /**
     * The bug the lock prevents, reproduced on purpose with plain SQL: both transactions read the balance before
     * either writes. Every database rule still passes, because each entry is balanced on its own.
     */
    @Test
    void checkThenInsertWithoutALockOverdraws() throws Exception {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Callable<Object> naiveTransfer = () -> tx.execute(status -> {
            long seen = jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = ?", Long.class, alice);
            log.info("naive transfer read balance={}", seen);
            await(bothHaveRead);
            if (seen >= 1000) {
                UUID entry = UUID.randomUUID();
                jdbc.update("INSERT INTO journal_entry (id, kind, created_at) VALUES (?, 'TRANSFER', now())", entry);
                jdbc.update("INSERT INTO posting (id, entry_id, account_id, amount_minor, currency) VALUES (?, ?, ?, -1000, 'USD')",
                    UUID.randomUUID(), entry, alice);
                jdbc.update("INSERT INTO posting (id, entry_id, account_id, amount_minor, currency) VALUES (?, ?, ?, 1000, 'USD')",
                    UUID.randomUUID(), entry, bob);
                log.info("naive transfer inserted a $10.00 debit");
            }
            return null;
        });

        for (Future<Object> f : raceAll(List.of(naiveTransfer, naiveTransfer))) {
            f.get(10, TimeUnit.SECONDS);
        }

        log.info("after two naive $10.00 transfers from a $10.00 balance: alice={} bob={}", balance(alice), balance(bob));
        assertThat(balance(alice)).isEqualTo(-1000);
        assertThat(balance(bob)).isEqualTo(2000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
