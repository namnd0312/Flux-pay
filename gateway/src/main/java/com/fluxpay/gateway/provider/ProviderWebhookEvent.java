package com.fluxpay.gateway.provider;

/**
 * A webhook parsed into the gateway's neutral shape. {@code merchantRef} is the gateway
 * payment id (as a string); {@code status} is SUCCESS or FAILED. occurredAt is carried for
 * the record but not used for logic in increment 1.
 */
public record ProviderWebhookEvent(
        String eventId,
        String providerRef,
        String merchantRef,
        String status,
        long amount,
        String occurredAt) {
}
