package dev.p2plab.payments.ledger;

import dev.p2plab.payments.account.api.AccountResponse;
import dev.p2plab.payments.account.api.BalanceResponse;
import dev.p2plab.payments.ledger.api.JournalEntryResponse;
import dev.p2plab.payments.ledger.domain.LedgerAccount;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger end to end over HTTP against a real PostgreSQL, plus the V3 database rules that hold even when
 * the application is bypassed. Requires a running Docker daemon.
 */
@Testcontainers
@ActiveProfiles("lab")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void fundingMovesMoneyOutOfTheFundingSourceAndTheWholeLedgerStillSumsToZero() {
        UUID alice = openAccount("Alice Example");

        ResponseEntity<JournalEntryResponse> funding = fund(alice, 1000);

        assertThat(funding.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(funding.getBody().postings()).extracting(JournalEntryResponse.PostingResponse::amountMinor)
            .containsExactly(-1000L, 1000L);
        assertThat(balance(alice)).isEqualTo(1000);
        assertThat(balance(LedgerAccount.FUNDING_SOURCE_ID)).isNegative();
        assertThat(jdbc.queryForObject("SELECT SUM(amount_minor) FROM posting", Long.class)).isZero();
    }

    @Test
    void fundingAnUnknownAccountIs404AndWritesNothing() {
        long postings = jdbc.queryForObject("SELECT count(*) FROM posting", Long.class);

        assertThat(fund(UUID.randomUUID(), 1000).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting", Long.class)).isEqualTo(postings);
    }

    @Test
    void databaseRejectsUnbalancedEntryAtCommitEvenWhenApplicationIsBypassed() {
        UUID alice = openAccount("Alice Example");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Each INSERT succeeds; the deferred constraint trigger fires at COMMIT and rejects the whole entry.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID entry = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_entry (id, kind, created_at) VALUES (?, 'FUNDING', now())", entry);
            jdbc.update("INSERT INTO posting (id, entry_id, account_id, amount_minor, currency) VALUES (?, ?, ?, 1000000, 'USD')",
                UUID.randomUUID(), entry, alice);
        })).rootCause().hasMessageContaining("is unbalanced: 1 postings summing to 1000000");

        assertThat(balance(alice)).isZero();
    }

    @Test
    void databaseRejectsEditingOrDeletingHistory() {
        UUID alice = openAccount("Alice Example");
        fund(alice, 1000);

        assertThatThrownBy(() -> jdbc.update("UPDATE posting SET amount_minor = 999999 WHERE account_id = ?", alice))
            .isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("ledger is append-only: UPDATE on posting");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entry"))
            .isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("ledger is append-only: DELETE on journal_entry");
        assertThat(balance(alice)).isEqualTo(1000);
    }

    private UUID openAccount(String name) {
        return http.postForObject("/api/v1/accounts", Map.of("displayName", name, "currency", "USD"), AccountResponse.class).id();
    }

    private ResponseEntity<JournalEntryResponse> fund(UUID account, long amountMinor) {
        return http.postForEntity("/lab/ledger/funding",
            Map.of("accountId", account, "amountMinor", amountMinor, "currency", "USD"), JournalEntryResponse.class);
    }

    private long balance(UUID account) {
        BalanceResponse balance = http.getForObject("/api/v1/accounts/" + account + "/balance", BalanceResponse.class);
        assertThat(balance.availableMinor()).isEqualTo(balance.ledgerMinor());
        return balance.ledgerMinor();
    }
}
