package dev.p2plab.payments.payment.api;

import dev.p2plab.payments.money.Currency;
import dev.p2plab.payments.money.Money;
import dev.p2plab.payments.payment.application.PaymentCommand;
import dev.p2plab.payments.payment.application.PaymentService;
import dev.p2plab.payments.payment.application.PaymentService.PaymentOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for payments: validates and translates, nothing more.
 * <ul>
 *   <li>201 Created: the payment reached a final state (COMPLETED or REJECTED; the body says which).</li>
 *   <li>202 Accepted: it is recorded but not finished; retry with the same Idempotency-Key to resume it.</li>
 *   <li>A replay returns the same status code and body, plus {@code Idempotent-Replayed: true}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    /** Until Chapter 5, the caller says which account is paying. Nothing checks that it is theirs. */
    static final String PAYER_HEADER = "X-Payer-Account-Id";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> pay(
            @RequestHeader(PAYER_HEADER) UUID payerId,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "1-64 letters, digits or . _ : -") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentOutcome outcome = paymentService.pay(new PaymentCommand(payerId, idempotencyKey, request.payeeAccountId(),
            new Money(request.amountMinor(), Currency.valueOf(request.currency()))));

        PaymentResponse body = PaymentResponse.from(outcome.payment());
        HttpStatus status = outcome.payment().status().isFinal() ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
            .location(URI.create("/api/v1/payments/" + body.id()))
            .header(REPLAYED_HEADER, Boolean.toString(outcome.replayed()))
            .body(body);
    }

    /** No ownership check yet: anyone who knows the id can read the payment. Chapter 5 adds it. */
    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }
}
