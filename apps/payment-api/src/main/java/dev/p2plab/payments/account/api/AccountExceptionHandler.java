package dev.p2plab.payments.account.api;

import dev.p2plab.payments.account.application.AccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps account errors to RFC 9457 problem responses. The detail never echoes caller input. */
@RestControllerAdvice(assignableTypes = AccountController.class)
class AccountExceptionHandler {
    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account not found");
        problem.setTitle("Account not found");
        return problem;
    }
}
