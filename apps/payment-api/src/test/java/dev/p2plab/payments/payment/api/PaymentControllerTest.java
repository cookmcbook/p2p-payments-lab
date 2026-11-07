package dev.p2plab.payments.payment.api;

import dev.p2plab.payments.ledger.application.UnknownLedgerAccountException;
import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import dev.p2plab.payments.payment.application.IdempotencyKeyReusedException;
import dev.p2plab.payments.payment.application.PaymentCommand;
import dev.p2plab.payments.payment.application.PaymentNotFoundException;
import dev.p2plab.payments.payment.application.PaymentService;
import dev.p2plab.payments.payment.application.PaymentService.PaymentOutcome;
import dev.p2plab.payments.payment.domain.Payment;
import dev.p2plab.payments.payment.domain.RejectionReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the payment API, with the service mocked. */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ENTRY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final Money FIVE = new Money(500, Currency.USD);
    private static final String BODY = "{\"payeeAccountId\":\"" + B + "\",\"amountMinor\":%d,\"currency\":\"USD\"}";
    private static final PaymentCommand COMMAND = new PaymentCommand(A, "key-1", B, FIVE);

    @Autowired MockMvc mvc;
    @MockitoBean PaymentService paymentService;

    private static MockHttpServletRequestBuilder pay(long amountMinor) {
        return post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
            .header("X-Payer-Account-Id", A.toString()).header("Idempotency-Key", "key-1")
            .content(BODY.formatted(amountMinor));
    }

    private static Payment accepted() {
        return Payment.accepted(ID, A, B, FIVE, HOLD, CLOCK);
    }

    @Test
    void completedPaymentIs201WithLocationAndFinalState() throws Exception {
        given(paymentService.pay(COMMAND)).willReturn(new PaymentOutcome(accepted().startProcessing(CLOCK).complete(ENTRY, CLOCK), false));

        mvc.perform(pay(500))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/payments/" + ID))
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.payerAccountId").value(A.toString()))
            .andExpect(jsonPath("$.amountMinor").value(500))
            .andExpect(jsonPath("$.journalEntryId").value(ENTRY.toString()))
            .andExpect(jsonPath("$.rejectionReason").doesNotExist());
    }

    @Test
    void rejectedPaymentIsAlso201BecauseItIsAFinalRecordedOutcome() throws Exception {
        given(paymentService.pay(COMMAND)).willReturn(new PaymentOutcome(
            Payment.rejected(ID, A, B, FIVE, RejectionReason.INSUFFICIENT_FUNDS, CLOCK), false));

        mvc.perform(pay(500))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectionReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void unfinishedPaymentIs202() throws Exception {
        given(paymentService.pay(COMMAND)).willReturn(new PaymentOutcome(accepted().startProcessing(CLOCK), false));

        mvc.perform(pay(500)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void replayIsMarkedWithAHeader() throws Exception {
        given(paymentService.pay(COMMAND)).willReturn(new PaymentOutcome(accepted().startProcessing(CLOCK).complete(ENTRY, CLOCK), true));

        mvc.perform(pay(500)).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void keyReusedForADifferentRequestIs422() throws Exception {
        given(paymentService.pay(COMMAND)).willThrow(new IdempotencyKeyReusedException());

        mvc.perform(pay(500))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Idempotency-Key was already used for a different request"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        given(paymentService.pay(COMMAND)).willThrow(new UnknownLedgerAccountException(B));
        mvc.perform(pay(500)).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Account not found"));
    }

    @Test
    void ruleViolationIs400() throws Exception {
        given(paymentService.pay(COMMAND)).willThrow(new IllegalArgumentException("same account"));
        mvc.perform(pay(500)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Invalid payment"));
    }

    @Test
    void missingOrMalformedHeadersAndBadAmountsNeverReachTheService() throws Exception {
        String body = BODY.formatted(500);
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Payer-Account-Id", A.toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Payer-Account-Id", "not-a-uuid").header("Idempotency-Key", "key-1"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Payer-Account-Id", A.toString()).header("Idempotency-Key", "spaces are not allowed"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Payer-Account-Id", A.toString()).header("Idempotency-Key", "k".repeat(65)))
            .andExpect(status().isBadRequest());
        for (long amount : new long[] {0, -500, 100_000_001}) {
            mvc.perform(pay(amount)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(paymentService);
    }

    @Test
    void getReturnsThePaymentOr404() throws Exception {
        given(paymentService.get(ID)).willReturn(accepted());
        mvc.perform(get("/api/v1/payments/{id}", ID)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.holdId").value(HOLD.toString()));

        given(paymentService.get(any())).willThrow(new PaymentNotFoundException(ID));
        mvc.perform(get("/api/v1/payments/{id}", ID)).andExpect(status().isNotFound());
    }
}
