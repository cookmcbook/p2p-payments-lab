package dev.p2plab.payments.payment.domain;

import dev.p2plab.payments.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A short, fixed-length summary of what a payment request asked for. Two requests with the same idempotency key
 * must have the same fingerprint, or the second one is refused. Built from the parsed values, not the raw JSON,
 * so whitespace or field order don't matter. The payer isn't included: it is already part of the key's scope.
 */
public final class RequestFingerprint {
    private static final String VERSION = "v1";

    private RequestFingerprint() {
    }

    /** SHA-256 of {@code v1|payee|amountMinor|currency}, as 64 lowercase hex characters. */
    public static String of(UUID payeeId, Money amount) {
        String canonical = String.join("|", VERSION, payeeId.toString(), Long.toString(amount.amountMinor()),
            amount.currency().name());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime must provide SHA-256", e);
        }
    }
}
