package dev.p2plab.payments.payment.api;

import dev.p2plab.payments.ledger.application.UnknownLedgerAccountException;
import dev.p2plab.payments.payment.application.IdempotencyKeyReusedException;
import dev.p2plab.payments.payment.application.PaymentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps payment errors to RFC 9457 problem responses. The detail never echoes caller input. Insufficient funds is
 * not an error here: it is a REJECTED payment, returned with 201 like any other final outcome.
 */
@RestControllerAdvice(assignableTypes = PaymentController.class)
class PaymentExceptionHandler {
    @ExceptionHandler(UnknownLedgerAccountException.class)
    ProblemDetail unknownAccount() {
        return problem(HttpStatus.NOT_FOUND, "Account not found");
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail unknownPayment() {
        return problem(HttpStatus.NOT_FOUND, "Payment not found");
    }

    /** 422: well-formed, but this key already means a different payment. Use a new key for a new payment. */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused() {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was already used for a different request");
    }

    /** Domain rule violations, such as paying yourself or paying the funding account. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid payment");
    }

    private static ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, title);
        problem.setTitle(title);
        return problem;
    }
}
