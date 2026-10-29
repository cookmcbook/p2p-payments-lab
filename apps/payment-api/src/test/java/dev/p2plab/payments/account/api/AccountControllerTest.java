package dev.p2plab.payments.account.api;

import dev.p2plab.payments.account.application.AccountBalance;
import dev.p2plab.payments.account.application.AccountNotFoundException;
import dev.p2plab.payments.account.application.AccountService;
import dev.p2plab.payments.account.domain.Account;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract tests: request validation, status codes and response shape, with the service mocked. */
@WebMvcTest(AccountController.class)
class AccountControllerTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Account ACCOUNT = new Account(ID, "Alice", Currency.USD, Instant.parse("2026-01-01T00:00:00Z"));

    @Autowired MockMvc mvc;
    @MockitoBean AccountService accountService;

    @Test
    void createReturns201WithLocation() throws Exception {
        given(accountService.open("Alice", Currency.USD)).willReturn(ACCOUNT);

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"currency\":\"USD\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/accounts/" + ID))
            .andExpect(jsonPath("$.id").value(ID.toString()))
            .andExpect(jsonPath("$.displayName").value("Alice"))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void createRejectsUnsupportedCurrency() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"currency\":\"EUR\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(accountService);
    }

    @Test
    void createRejectsBlankDisplayName() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"  \",\"currency\":\"USD\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @Test
    void createRejectsOverlongDisplayName() throws Exception {
        String name = "x".repeat(Account.MAX_DISPLAY_NAME_LENGTH + 1);
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + name + "\",\"currency\":\"USD\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @Test
    void getReturnsAccount() throws Exception {
        given(accountService.get(ID)).willReturn(ACCOUNT);

        mvc.perform(get("/api/v1/accounts/{id}", ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void unknownAccountReturns404Problem() throws Exception {
        given(accountService.get(any())).willThrow(new AccountNotFoundException(ID));

        mvc.perform(get("/api/v1/accounts/{id}", ID))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Account not found"))
            .andExpect(jsonPath("$.detail").value("Account not found"));
    }

    @Test
    void malformedIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", "not-a-uuid"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(accountService);
    }

    @Test
    void balanceIsReportedInMinorUnits() throws Exception {
        given(accountService.balance(ID)).willReturn(new AccountBalance(ID, Money.zero(Currency.USD), Money.zero(Currency.USD)));

        mvc.perform(get("/api/v1/accounts/{id}/balance", ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(ID.toString()))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.ledgerMinor").value(0))
            .andExpect(jsonPath("$.availableMinor").value(0));
    }
}
