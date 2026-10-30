package dev.p2plab.payments.ledger.api;

import dev.p2plab.payments.ledger.application.LedgerService;
import dev.p2plab.payments.ledger.application.UnknownLedgerAccountException;
import dev.p2plab.payments.ledger.domain.EntryKind;
import dev.p2plab.payments.ledger.domain.InsufficientFundsException;
import dev.p2plab.payments.ledger.domain.JournalEntry;
import dev.p2plab.payments.ledger.domain.Posting;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the lab-only ledger endpoints, with the service mocked. */
@WebMvcTest(LabLedgerController.class)
@ActiveProfiles("lab")
class LabLedgerControllerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Money FIVE_DOLLARS = new Money(500, Currency.USD);
    private static final String TRANSFER = "{\"fromAccountId\":\"" + A + "\",\"toAccountId\":\"" + B + "\",\"amountMinor\":%d,\"currency\":\"USD\"}";

    @Autowired MockMvc mvc;
    @MockitoBean LedgerService ledgerService;

    @Test
    void transferReturns201WithSignedPostings() throws Exception {
        JournalEntry entry = new JournalEntry(UUID.fromString("00000000-0000-0000-0000-0000000000e1"), EntryKind.TRANSFER,
            Instant.parse("2026-01-01T00:00:00Z"), List.of(new Posting(A, FIVE_DOLLARS.negate()), new Posting(B, FIVE_DOLLARS)));
        given(ledgerService.transfer(A, B, FIVE_DOLLARS)).willReturn(entry);

        mvc.perform(post("/lab/ledger/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER.formatted(500)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.kind").value("TRANSFER"))
            .andExpect(jsonPath("$.postings[0].accountId").value(A.toString()))
            .andExpect(jsonPath("$.postings[0].amountMinor").value(-500))
            .andExpect(jsonPath("$.postings[1].amountMinor").value(500));
    }

    @Test
    void insufficientFundsIs409() throws Exception {
        given(ledgerService.transfer(A, B, FIVE_DOLLARS)).willThrow(new InsufficientFundsException(A));

        mvc.perform(post("/lab/ledger/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER.formatted(500)))
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Insufficient funds"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        given(ledgerService.transfer(A, B, FIVE_DOLLARS)).willThrow(new UnknownLedgerAccountException(B));

        mvc.perform(post("/lab/ledger/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER.formatted(500)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Account not found"));
    }

    @Test
    void domainRuleViolationIs400() throws Exception {
        given(ledgerService.transfer(A, B, FIVE_DOLLARS)).willThrow(new IllegalArgumentException("same account"));

        mvc.perform(post("/lab/ledger/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER.formatted(500)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid journal entry"));
    }

    @Test
    void zeroNegativeAndOversizedAmountsAreRejectedBeforeTheService() throws Exception {
        for (long amount : new long[] {0, -500, 100_000_001}) {
            mvc.perform(post("/lab/ledger/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER.formatted(amount)))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(ledgerService);
    }

    @Test
    void fundingRequiresAnAccount() throws Exception {
        mvc.perform(post("/lab/ledger/funding").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":500,\"currency\":\"USD\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(ledgerService);
    }
}
