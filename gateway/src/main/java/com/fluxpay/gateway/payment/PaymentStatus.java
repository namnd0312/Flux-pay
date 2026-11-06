package com.fluxpay.gateway.payment;

/**
 * The states a payment can occupy. CREATED and PENDING are live; the rest are terminal
 * (the state machine allows no transition out of them). The set of legal moves between
 * these states lives in {@link PaymentStateMachine}, not here.
 */
public enum PaymentStatus {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED
}
