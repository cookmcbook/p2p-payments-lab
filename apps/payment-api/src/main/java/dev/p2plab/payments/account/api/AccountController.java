package dev.p2plab.payments.account.api;

import dev.p2plab.payments.account.application.AccountService;
import dev.p2plab.payments.account.domain.Account;
import dev.p2plab.payments.money.Currency;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for accounts: validates and translates, nothing more. No list endpoint until auth exists. */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.open(request.displayName(), Currency.valueOf(request.currency()));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        return BalanceResponse.from(accountService.balance(id));
    }
}
