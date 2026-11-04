package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.service.IdempotencyKeyInFlightException;
import com.gopi.payments.payment_api.service.IdempotencyKeyReusedException;
import com.gopi.payments.payment_api.service.InvalidPaymentRequestException;
import com.gopi.payments.payment_api.service.PaymentNotFoundException;
import com.gopi.payments.payment_api.service.PaymentNotReviewableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 ProblemDetail. The base class covers bad JSON, failed bean validation, a missing header
 * and bad path variables (400); the handlers below map this service's own exceptions.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidPaymentRequestException.class)
    ProblemDetail invalid(InvalidPaymentRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(PaymentNotReviewableException.class)
    ProblemDetail notReviewable(PaymentNotReviewableException e) {
        return problem(HttpStatus.CONFLICT, "Not reviewable", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInFlightException.class)
    ResponseEntity<ProblemDetail> inFlight(IdempotencyKeyInFlightException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.CONFLICT, "Request in progress", e.getMessage()));
    }

    /** Anything unexpected: logged here, and the client gets no stack trace or SQL. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
