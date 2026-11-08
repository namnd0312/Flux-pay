package com.fluxpay.gateway.idempotency;

/**
 * Thrown when a merchant tries to create a payment for an order that already has a live
 * payment (CREATED, PENDING or SUCCEEDED). Enforced by the partial unique index
 * {@code ux_payments_live_order}; the create flow maps the resulting integrity violation to
 * this exception, which the advice renders as 409 {@code ORDER_ALREADY_EXISTS}. A terminal
 * non-success attempt (FAILED, EXPIRED, CANCELLED) does not trigger it, so the order stays
 * retriable.
 */
public class OrderAlreadyExistsException extends RuntimeException {

    public OrderAlreadyExistsException(String orderId) {
        super("A live payment already exists for order " + orderId);
    }
}
