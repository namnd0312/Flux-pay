package com.fluxpay.gateway.provider;

/**
 * Strategy for talking to one payment provider (a bank/acquirer like VNPAY). The payment
 * core depends only on this interface, never on a concrete adapter, so adding a provider
 * is one new class plus config. Phase 4 adds webhook verify/parse; increment 4 adds a
 * status query.
 */
public interface PaymentProvider {

    /** Stable code used to resolve this provider (e.g. "MOCK"). Unique across all beans. */
    String code();

    /** Create a payment session at the provider and return where to send the customer. */
    ProviderPaymentResult createPayment(ProviderPaymentRequest request);
}
