package com.fluxpay.gateway.provider;

import java.util.UUID;

/**
 * What the gateway hands a provider to open a payment session. {@code merchantRef} is the
 * gateway payment id, echoed back on the webhook so the gateway can match the event.
 */
public record ProviderPaymentRequest(UUID merchantRef, long amount, String description, String returnUrl) {
}
