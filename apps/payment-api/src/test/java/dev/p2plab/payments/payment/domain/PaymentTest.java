package dev.p2plab.payments.payment.domain;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Payment lifecycle rules and request fingerprints. No Spring, no database. */
class PaymentTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC);
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ENTRY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final Money FIVE = new Money(500, Currency.USD);

    @Test
    void happyPathGoesAcceptedProcessingCompleted() {
        Payment accepted = Payment.accepted(ID, A, B, FIVE, HOLD, CLOCK);
        Payment processing = accepted.startProcessing(CLOCK);
        Payment completed = processing.complete(ENTRY, CLOCK);

        assertThat(accepted.status()).isEqualTo(PaymentStatus.ACCEPTED);
        assertThat(processing.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(completed.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(completed.journalEntryId()).isEqualTo(ENTRY);
        assertThat(completed.holdId()).isEqualTo(HOLD);
        assertThat(completed.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void cannotSkipProcessingOrMoveOutOfAFinalState() {
        Payment accepted = Payment.accepted(ID, A, B, FIVE, HOLD, CLOCK);
        Payment completed = accepted.startProcessing(CLOCK).complete(ENTRY, CLOCK);
        Payment rejected = Payment.rejected(ID, A, B, FIVE, RejectionReason.INSUFFICIENT_FUNDS, CLOCK);

        assertThatThrownBy(() -> accepted.complete(ENTRY, CLOCK)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot move from ACCEPTED to COMPLETED");
        assertThatThrownBy(() -> completed.startProcessing(CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.startProcessing(CLOCK)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transitionTable() {
        assertThat(PaymentStatus.ACCEPTED.canMoveTo(PaymentStatus.PROCESSING)).isTrue();
        assertThat(PaymentStatus.ACCEPTED.canMoveTo(PaymentStatus.COMPLETED)).isFalse();
        assertThat(PaymentStatus.PROCESSING.canMoveTo(PaymentStatus.INDETERMINATE)).isTrue();
        // Indeterminate is resolved only by reconciliation, to one of the final states; never back to a retry.
        assertThat(PaymentStatus.INDETERMINATE.canMoveTo(PaymentStatus.COMPLETED)).isTrue();
        assertThat(PaymentStatus.INDETERMINATE.canMoveTo(PaymentStatus.REJECTED)).isTrue();
        assertThat(PaymentStatus.INDETERMINATE.canMoveTo(PaymentStatus.PROCESSING)).isFalse();
        assertThat(PaymentStatus.INDETERMINATE.isFinal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"COMPLETED", "REJECTED"})
    void finalStatesGoNowhere(PaymentStatus status) {
        assertThat(status.isFinal()).isTrue();
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(status.canMoveTo(next)).isFalse();
        }
    }

    @Test
    void rejectedCarriesAReasonAndNoHold() {
        Payment rejected = Payment.rejected(ID, A, B, FIVE, RejectionReason.INSUFFICIENT_FUNDS, CLOCK);

        assertThat(rejected.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        assertThat(rejected.holdId()).isNull();
        assertThat(rejected.journalEntryId()).isNull();
    }

    @Test
    void rejectsSelfPaymentNonPositiveAmountsAndAcceptedWithoutAHold() {
        assertThatThrownBy(() -> Payment.accepted(ID, A, A, FIVE, HOLD, CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.accepted(ID, A, B, new Money(0, Currency.USD), HOLD, CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.accepted(ID, A, B, FIVE, null, CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintIsStableAndChangesWithAnyField() {
        String original = RequestFingerprint.of(B, FIVE);

        assertThat(original).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(RequestFingerprint.of(B, new Money(500, Currency.USD))).isEqualTo(original);
        assertThat(RequestFingerprint.of(B, new Money(501, Currency.USD))).isNotEqualTo(original);
        assertThat(RequestFingerprint.of(A, FIVE)).isNotEqualTo(original);
    }
}
