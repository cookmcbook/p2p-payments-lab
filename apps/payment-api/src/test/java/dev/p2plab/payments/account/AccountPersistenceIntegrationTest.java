package dev.p2plab.payments.account;

import dev.p2plab.payments.account.api.AccountResponse;
import dev.p2plab.payments.account.api.BalanceResponse;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full stack against a real PostgreSQL: HTTP → controller → service → JPA → Flyway-managed schema.
 * Requires a running Docker daemon.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountPersistenceIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAppliedAccountMigration() {
        Integer applied = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version IN ('2', '3') AND success", Integer.class);
        assertThat(applied).isEqualTo(2);
    }

    @Test
    void createdAccountIsPersistedAndReadableWithZeroBalance() {
        ResponseEntity<AccountResponse> created = http.postForEntity(
            "/api/v1/accounts", Map.of("displayName", "Alice Example", "currency", "USD"), AccountResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = created.getHeaders().getLocation();
        UUID id = created.getBody().id();
        assertThat(location).hasPath("/api/v1/accounts/" + id);

        AccountResponse fetched = http.getForObject(location, AccountResponse.class);
        assertThat(fetched).isEqualTo(created.getBody());

        BalanceResponse balance = http.getForObject(location + "/balance", BalanceResponse.class);
        assertThat(balance).isEqualTo(new BalanceResponse(id, "USD", 0, 0));

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Integer.class, id);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void labLedgerEndpointsDoNotExistWithoutTheLabProfile() {
        ResponseEntity<String> response = http.postForEntity("/lab/ledger/funding",
            Map.of("accountId", UUID.randomUUID(), "amountMinor", 100, "currency", "USD"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownAccountIs404() {
        ResponseEntity<String> response = http.getForEntity("/api/v1/accounts/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void databaseRejectsNonUsdCurrencyEvenWhenApplicationIsBypassed() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO account (id, display_name, currency, created_at) VALUES (?, 'Mallory', 'EUR', now())",
            UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsBlankDisplayNameEvenWhenApplicationIsBypassed() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO account (id, display_name, currency, created_at) VALUES (?, '   ', 'USD', now())",
            UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
