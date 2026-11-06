package com.fluxpay.gateway.provider;

import java.util.Map;

/**
 * Strategy for talking to one payment provider (a bank/acquirer like VNPAY). The payment
 * core depends only on this interface, never on a concrete adapter, so adding a provider
 * is one new class plus config. Increment 4 adds a status query.
 */
public interface PaymentProvider {

    /** Stable code used to resolve this provider (e.g. "MOCK"). Unique across all beans. */
    String code();

    /** Create a payment session at the provider and return where to send the customer. */
    ProviderPaymentResult createPayment(ProviderPaymentRequest request);

    /**
     * Verify an inbound webhook's signature over the exact raw bytes received. Each
     * provider knows which header carries its signature. Returns false on any mismatch.
     */
    boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers);

    /** Parse a (signature-verified) webhook body into the gateway's neutral event shape. */
    ProviderWebhookEvent parseWebhook(byte[] rawBody);
}
