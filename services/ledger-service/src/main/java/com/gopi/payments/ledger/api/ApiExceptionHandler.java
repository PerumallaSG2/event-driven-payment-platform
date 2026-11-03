package com.gopi.payments.ledger.api;

import com.gopi.payments.ledger.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 ProblemDetail. The base class covers bad JSON, failed bean validation and bad path
 * variables (400); the handlers below map the ledger's own exceptions.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidEntryException.class)
    ProblemDetail invalid(InvalidEntryException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e);
    }

    @ExceptionHandler(LedgerNotFoundException.class)
    ProblemDetail notFound(LedgerNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e);
    }

    @ExceptionHandler(HoldNotOpenException.class)
    ProblemDetail holdNotOpen(HoldNotOpenException e) {
        return problem(HttpStatus.CONFLICT, "Hold already settled", e);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", e);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch", e);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
