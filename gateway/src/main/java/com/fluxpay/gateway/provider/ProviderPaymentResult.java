package com.fluxpay.gateway.provider;

/** What a provider returns: its own reference and the URL to send the customer to. */
public record ProviderPaymentResult(String providerRef, String payUrl) {
}
