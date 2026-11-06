package com.fluxpay.mockprovider.session;

/**
 * A payment session held by the fake provider. {@code merchantRef} is the gateway payment
 * id; {@code status} starts NEW. The actual "customer pays" action and the status change
 * arrive in phase 4.
 */
public record PaymentSession(
        String providerRef,
        String merchantRef,
        long amount,
        String description,
        String returnUrl,
        String status) {

    /** Return a copy with a new status (records are immutable). */
    public PaymentSession withStatus(String newStatus) {
        return new PaymentSession(providerRef, merchantRef, amount, description, returnUrl, newStatus);
    }
}
