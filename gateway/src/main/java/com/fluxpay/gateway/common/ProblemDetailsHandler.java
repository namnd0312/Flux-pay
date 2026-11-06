package com.fluxpay.gateway.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fluxpay.gateway.merchant.UnknownMerchantException;
import com.fluxpay.gateway.payment.IllegalPaymentTransitionException;
import com.fluxpay.gateway.payment.PaymentNotFoundException;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.UnknownProviderException;

/**
 * Maps exceptions to RFC 7807 problem details with a stable {@code code} field. Each
 * handler names the invariant it protects. The catch-all keeps unexpected failures from
 * leaking a stack trace to a merchant. Bodies are built by the shared
 * {@link ProblemDetailWriter} so advice and filters (phases 5-6) agree.
 */
@RestControllerAdvice
public class ProblemDetailsHandler {

    private final ProblemDetailWriter writer;

    public ProblemDetailsHandler(ProblemDetailWriter writer) {
        this.writer = writer;
    }

    /** Unknown/inactive/missing merchant: identity could not be established. */
    @ExceptionHandler(UnknownMerchantException.class)
    public ProblemDetail onUnknownMerchant(UnknownMerchantException ex) {
        return writer.build(HttpStatus.UNAUTHORIZED, "AUTH_UNKNOWN_MERCHANT", ex.getMessage());
    }

    /** Payment id absent or owned by another merchant: indistinguishable on purpose. */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail onPaymentNotFound(PaymentNotFoundException ex) {
        return writer.build(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage());
    }

    /** A move the transition table forbids (e.g. cancel a terminal payment). */
    @ExceptionHandler(IllegalPaymentTransitionException.class)
    public ProblemDetail onIllegalTransition(IllegalPaymentTransitionException ex) {
        return writer.build(HttpStatus.CONFLICT, "PAYMENT_ILLEGAL_TRANSITION", ex.getMessage());
    }

    /** Requested provider code has no adapter. */
    @ExceptionHandler(UnknownProviderException.class)
    public ProblemDetail onUnknownProvider(UnknownProviderException ex) {
        return writer.build(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVIDER", ex.getMessage());
    }

    /** Provider call failed. The cause is already logged; the body carries no exception text. */
    @ExceptionHandler(ProviderException.class)
    public ProblemDetail onProviderFailure(ProviderException ex) {
        return writer.build(HttpStatus.BAD_GATEWAY, ex.getReasonCode(), "Payment provider is temporarily unavailable.");
    }

    /** Bean-validation failure on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return writer.build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex) {
        return writer.build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.");
    }
}
