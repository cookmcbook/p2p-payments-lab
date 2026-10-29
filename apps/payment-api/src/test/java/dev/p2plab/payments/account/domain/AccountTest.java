package dev.p2plab.payments.account.domain;

import dev.p2plab.payments.money.Currency;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AccountTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void openAssignsIdAndCreationTimeFromClock() {
        Account account = Account.open("Alice Example", Currency.USD, clock);

        assertThat(account.id()).isNotNull();
        assertThat(account.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(account.currency()).isEqualTo(Currency.USD);
    }

    @Test
    void truncatesCreationTimeToDatabasePrecision() {
        Clock nanoClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC);
        assertThat(Account.open("Alice", Currency.USD, nanoClock).createdAt())
            .isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void stripsSurroundingWhitespaceFromDisplayName() {
        assertThat(Account.open("  Alice  ", Currency.USD, clock).displayName()).isEqualTo("Alice");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsBlankDisplayName(String displayName) {
        assertThatIllegalArgumentException().isThrownBy(() -> Account.open(displayName, Currency.USD, clock));
    }

    @Test
    void rejectsDisplayNameLongerThanLimit() {
        String tooLong = "x".repeat(Account.MAX_DISPLAY_NAME_LENGTH + 1);
        assertThatIllegalArgumentException().isThrownBy(() -> Account.open(tooLong, Currency.USD, clock));
    }

    @Test
    void acceptsDisplayNameAtLimit() {
        String atLimit = "x".repeat(Account.MAX_DISPLAY_NAME_LENGTH);
        assertThat(Account.open(atLimit, Currency.USD, clock).displayName()).hasSize(Account.MAX_DISPLAY_NAME_LENGTH);
    }

    @Test
    void requiresCurrency() {
        assertThatNullPointerException().isThrownBy(() -> Account.open("Alice", null, clock));
    }
}
