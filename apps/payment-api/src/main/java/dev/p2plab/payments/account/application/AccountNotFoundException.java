package dev.p2plab.payments.account.application;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID id) {
        super("Account not found: " + id);
    }
}
