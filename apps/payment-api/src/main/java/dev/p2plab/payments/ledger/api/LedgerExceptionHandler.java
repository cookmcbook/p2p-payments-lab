package dev.p2plab.payments.ledger.api;

import dev.p2plab.payments.ledger.application.UnknownLedgerAccountException;
import dev.p2plab.payments.ledger.domain.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps ledger errors to RFC 9457 problem responses. The detail never echoes caller input. */
@RestControllerAdvice(assignableTypes = LabLedgerController.class)
class LedgerExceptionHandler {
    @ExceptionHandler(UnknownLedgerAccountException.class)
    ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "Account not found");
    }

    /** 409: the request is well-formed, but conflicts with the account's current state. */
    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds() {
        return problem(HttpStatus.CONFLICT, "Insufficient funds");
    }

    /** Domain rule violations, such as sending money from an account to itself. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid journal entry");
    }

    private static ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, title);
        problem.setTitle(title);
        return problem;
    }
}
