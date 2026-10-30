package dev.p2plab.payments.ledger.application;

import java.util.UUID;

public class UnknownLedgerAccountException extends RuntimeException {
    public UnknownLedgerAccountException(UUID id) {
        super("Account not found: " + id);
    }
}
