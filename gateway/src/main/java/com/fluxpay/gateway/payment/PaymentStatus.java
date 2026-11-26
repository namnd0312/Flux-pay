package com.fluxpay.gateway.payment;

/**
 * The states a payment can occupy. CREATED, UNDER_REVIEW and PENDING are live (the payment can
 * still end up paid); the rest are terminal (the state machine allows no transition out of
 * them). The set of legal moves between these states lives in {@link PaymentStateMachine},
 * not here.
 *
 * <p>UNDER_REVIEW is a payment a risk rule has held before the provider was ever called: it
 * has no provider reference and no pay URL until an operator decides.
 *
 * <p>The live-order unique index ({@code ux_payments_live_order}) lists the non-failed states
 * by name in SQL. A new live state must be added there as well as here.
 */
public enum PaymentStatus {
    CREATED,
    UNDER_REVIEW,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED
}
