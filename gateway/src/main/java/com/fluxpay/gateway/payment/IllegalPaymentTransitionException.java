package com.fluxpay.gateway.payment;

/**
 * Thrown when code attempts a move the transition table forbids (e.g. a webhook trying
 * to mark an already EXPIRED payment SUCCEEDED). Mapped to HTTP 409 by the advice. This
 * is the guard that stops a late or duplicate event from overwriting a terminal state.
 */
public class IllegalPaymentTransitionException extends RuntimeException {

    public IllegalPaymentTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Illegal payment transition: " + from + " -> " + to);
    }
}
