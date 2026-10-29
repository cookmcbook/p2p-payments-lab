package dev.p2plab.payments.account.api;

import dev.p2plab.payments.account.domain.Account;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank @Size(max = Account.MAX_DISPLAY_NAME_LENGTH) String displayName,
    @NotNull @Pattern(regexp = "USD", message = "only USD is supported") String currency) {
}
