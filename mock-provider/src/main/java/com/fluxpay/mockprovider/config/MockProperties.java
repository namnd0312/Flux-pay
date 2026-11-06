package com.fluxpay.mockprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the fake provider. {@code secret} signs outbound webhooks (must match the
 * gateway's provider webhook-secret); {@code gatewayWebhookUrl} is where webhooks are
 * sent; {@code publicBaseUrl} forms the pay URL. {@code callback} tweaks delivery to
 * simulate real-world misbehaviour (delay, duplicate sends) for the phase-4 demos.
 */
@ConfigurationProperties(prefix = "mock")
public record MockProperties(
        String secret,
        String gatewayWebhookUrl,
        String publicBaseUrl,
        Callback callback) {

    public record Callback(long delayMs, int duplicates) {
    }
}
