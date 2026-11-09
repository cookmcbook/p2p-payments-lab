package dev.p2plab.payments.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void addsExactly() {
        assertThat(new Money(1999, Currency.USD).plus(new Money(1, Currency.USD))).isEqualTo(new Money(2000, Currency.USD));
    }

    @Test
    void overflowFailsLoudlyInsteadOfWrappingNegative() {
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, Currency.USD).plus(new Money(1, Currency.USD)))
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE, Currency.USD).negate())
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(new Money(500, Currency.USD).negate()).isEqualTo(new Money(-500, Currency.USD));
        assertThat(new Money(-1, Currency.USD).isNegative()).isTrue();
        assertThat(Money.zero(Currency.USD).isNegative()).isFalse();
    }
}
