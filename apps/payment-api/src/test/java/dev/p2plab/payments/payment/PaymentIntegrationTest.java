package dev.p2plab.payments.payment;

import dev.p2plab.payments.account.api.AccountResponse;
import dev.p2plab.payments.account.api.BalanceResponse;
import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.ledger.domain.InsufficientFundsException;
import dev.p2plab.payments.ledger.domain.LedgerAccount;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import dev.p2plab.payments.payment.api.PaymentResponse;
import dev.p2plab.payments.payment.application.IdempotencyKeyPurger;
import dev.p2plab.payments.payment.application.PaymentCommand;
import dev.p2plab.payments.payment.application.PaymentSteps;
import dev.p2plab.payments.payment.domain.Payment;
import dev.p2plab.payments.payment.domain.PaymentStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment API end to end over HTTP against a real PostgreSQL: lifecycle, holds, idempotency and retention.
 * The lab profile is on only so tests can fund accounts. Requires a running Docker daemon.
 */
@Testcontainers
@ActiveProfiles("lab")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentSteps steps;
    @Autowired LedgerService ledger;
    @Autowired IdempotencyKeyPurger purger;

    @Test
    void paymentCompletesMovesMoneyOnceAndCapturesItsHold() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);

        ResponseEntity<PaymentResponse> response = pay(alice, "first", bob, 300, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        PaymentResponse payment = response.getBody();
        assertThat(payment.status()).isEqualTo("COMPLETED");
        assertThat(balance(alice)).isEqualTo(new BalanceResponse(alice, "USD", 700, 700));
        assertThat(balance(bob)).isEqualTo(new BalanceResponse(bob, "USD", 300, 300));
        assertThat(jdbc.queryForObject("SELECT status FROM ledger_hold WHERE id = ?", String.class, payment.holdId()))
            .isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT kind FROM journal_entry WHERE id = ?", String.class, payment.journalEntryId()))
            .isEqualTo("PAYMENT");
        assertThat(jdbc.queryForObject("SELECT SUM(amount_minor) FROM posting", Long.class)).isZero();
        assertThat(http.getForObject("/api/v1/payments/" + payment.id(), PaymentResponse.class)).isEqualTo(payment);
    }

    @Test
    void insufficientFundsIsAStoredRejectionAndMovesNothing() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);

        ResponseEntity<PaymentResponse> response = pay(alice, "too-much", bob, 1001, PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("REJECTED");
        assertThat(response.getBody().rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.getBody().holdId()).isNull();
        assertThat(balance(alice).ledgerMinor()).isEqualTo(1000);
        assertThat(balance(bob).ledgerMinor()).isZero();
    }

    @Test
    void sameKeyAndSamePayloadReturnsTheOriginalPaymentAndMovesMoneyOnce() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);

        PaymentResponse first = pay(alice, "retry-me", bob, 300, PaymentResponse.class).getBody();
        ResponseEntity<PaymentResponse> retry = pay(alice, "retry-me", bob, 300, PaymentResponse.class);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first);
        assertThat(balance(alice).ledgerMinor()).isEqualTo(700);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE payer_account_id = ?", Integer.class, alice)).isOne();
    }

    @Test
    void aReplayedRejectionStaysRejectedEvenAfterThePayerIsFunded() {
        UUID alice = funded("Alice Example", 100);
        UUID bob = funded("Bob Example", 0);
        PaymentResponse first = pay(alice, "too-early", bob, 500, PaymentResponse.class).getBody();
        ledger.fund(alice, new Money(1000, Currency.USD));

        PaymentResponse retry = pay(alice, "too-early", bob, 500, PaymentResponse.class).getBody();

        assertThat(retry).isEqualTo(first);
        assertThat(retry.status()).isEqualTo("REJECTED");
        assertThat(pay(alice, "a-new-key", bob, 500, PaymentResponse.class).getBody().status()).isEqualTo("COMPLETED");
    }

    @Test
    void sameKeyWithADifferentPayloadIs422AndMovesNothingMore() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        pay(alice, "one-key", bob, 300, PaymentResponse.class);

        ResponseEntity<String> response = pay(alice, "one-key", bob, 400, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("Idempotency-Key was already used for a different request");
        assertThat(balance(alice).ledgerMinor()).isEqualTo(700);
    }

    @Test
    void keysAreScopedToThePayer() {
        UUID alice = funded("Alice Example", 1000);
        UUID carol = funded("Carol Example", 1000);
        UUID bob = funded("Bob Example", 0);

        PaymentResponse fromAlice = pay(alice, "shared-key", bob, 100, PaymentResponse.class).getBody();
        PaymentResponse fromCarol = pay(carol, "shared-key", bob, 100, PaymentResponse.class).getBody();

        assertThat(fromCarol.id()).isNotEqualTo(fromAlice.id());
        assertThat(balance(bob).ledgerMinor()).isEqualTo(200);
    }

    @Test
    void anExpiredKeyMayBeReusedForANewPayment() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        PaymentResponse first = pay(alice, "old-key", bob, 100, PaymentResponse.class).getBody();
        expire(alice, "old-key");

        // The very same request again: after the retention window it is a NEW payment. That is the policy's cost.
        PaymentResponse second = pay(alice, "old-key", bob, 100, PaymentResponse.class).getBody();

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(balance(alice).ledgerMinor()).isEqualTo(800);
    }

    @Test
    void purgeDeletesOnlyExpiredKeysAndKeepsTheirPayments() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        PaymentResponse old = pay(alice, "purge-old", bob, 100, PaymentResponse.class).getBody();
        pay(alice, "purge-fresh", bob, 100, PaymentResponse.class);
        expire(alice, "purge-old");

        assertThat(purger.purge()).isGreaterThanOrEqualTo(1);

        assertThat(keyCount(alice, "purge-old")).isZero();
        assertThat(keyCount(alice, "purge-fresh")).isOne();
        assertThat(http.getForObject("/api/v1/payments/" + old.id(), PaymentResponse.class).status()).isEqualTo("COMPLETED");
    }

    @Test
    void aHoldLowersAvailableButNotLedgerAndAnotherDebitCannotSpendIt() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);

        // Only step 1, as if the service crashed straight after it: ACCEPTED, with the money held.
        Payment accepted = steps.accept(new PaymentCommand(alice, "crashed", bob, new Money(600, Currency.USD))).payment();

        assertThat(accepted.status()).isEqualTo(PaymentStatus.ACCEPTED);
        assertThat(balance(alice)).isEqualTo(new BalanceResponse(alice, "USD", 1000, 400));
        assertThatThrownBy(() -> ledger.transfer(alice, bob, new Money(500, Currency.USD)))
            .isInstanceOf(InsufficientFundsException.class);
        assertThat(pay(alice, "second", bob, 500, PaymentResponse.class).getBody().status()).isEqualTo("REJECTED");
    }

    @Test
    void retryingWithTheSameKeyResumesAPaymentLeftAcceptedOrProcessing() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        UUID left = steps.accept(new PaymentCommand(alice, "resume-a", bob, new Money(100, Currency.USD))).payment().id();
        UUID processing = steps.accept(new PaymentCommand(alice, "resume-p", bob, new Money(200, Currency.USD))).payment().id();
        steps.startProcessing(processing);
        assertThat(http.getForObject("/api/v1/payments/" + processing, PaymentResponse.class).status()).isEqualTo("PROCESSING");

        ResponseEntity<PaymentResponse> a = pay(alice, "resume-a", bob, 100, PaymentResponse.class);
        ResponseEntity<PaymentResponse> p = pay(alice, "resume-p", bob, 200, PaymentResponse.class);

        assertThat(a.getBody().id()).isEqualTo(left);
        assertThat(a.getBody().status()).isEqualTo("COMPLETED");
        assertThat(a.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(p.getBody().status()).isEqualTo("COMPLETED");
        assertThat(balance(alice)).isEqualTo(new BalanceResponse(alice, "USD", 700, 700));
    }

    @Test
    void invalidPartiesAreRefusedAndDoNotUseUpTheKey() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);

        assertThat(pay(alice, "k1", alice, 100, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(alice, "k2", LedgerAccount.FUNDING_SOURCE_ID, 100, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(LedgerAccount.FUNDING_SOURCE_ID, "k3", bob, 100, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pay(alice, "k4", UUID.randomUUID(), 100, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pay(UUID.randomUUID(), "k5", bob, 100, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Nothing was stored, so "k4" is still free for a valid request.
        assertThat(pay(alice, "k4", bob, 100, PaymentResponse.class).getBody().status()).isEqualTo("COMPLETED");
        assertThat(balance(alice).ledgerMinor()).isEqualTo(900);
    }

    @Test
    void databaseRejectsAPaymentStateThatContradictsItsFacts() {
        UUID alice = funded("Alice Example", 1000);
        UUID bob = funded("Bob Example", 0);
        PaymentResponse payment = pay(alice, "facts", bob, 100, PaymentResponse.class).getBody();

        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET journal_entry_id = NULL WHERE id = ?", payment.id()))
            .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("payment_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_hold SET status = 'ACTIVE' WHERE id = ?", payment.holdId()))
            .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ledger_hold_check");
    }

    private UUID funded(String name, long cents) {
        UUID id = http.postForObject("/api/v1/accounts", Map.of("displayName", name, "currency", "USD"), AccountResponse.class).id();
        if (cents > 0) {
            ledger.fund(id, new Money(cents, Currency.USD));
        }
        return id;
    }

    private <T> ResponseEntity<T> pay(UUID payer, String key, UUID payee, long amountMinor, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Payer-Account-Id", payer.toString());
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/v1/payments",
            new HttpEntity<>(Map.of("payeeAccountId", payee, "amountMinor", amountMinor, "currency", "USD"), headers), type);
    }

    private BalanceResponse balance(UUID account) {
        return http.getForObject("/api/v1/accounts/" + account + "/balance", BalanceResponse.class);
    }

    private void expire(UUID payer, String key) {
        jdbc.update("UPDATE idempotency_key SET created_at = now() - interval '25 hours', expires_at = now() - interval '1 hour' "
            + "WHERE payer_account_id = ? AND idempotency_key = ?", payer, key);
    }

    private int keyCount(UUID payer, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE payer_account_id = ? AND idempotency_key = ?",
            Integer.class, payer, key);
    }
}
