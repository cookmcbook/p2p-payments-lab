package dev.p2plab.payments.ledger.domain;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId) {
        super("Insufficient funds in account " + accountId);
    }
}
