package com.fluxpay.gateway.provider.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the mock provider adapter. {@code baseUrl} points at the mock-provider app;
 * {@code webhookSecret} is the shared HMAC key the mock-provider signs its webhooks with
 * (it must match {@code mock.secret} on the mock-provider side). Bound from
 * {@code fluxpay.providers.mock.*}.
 */
@ConfigurationProperties(prefix = "fluxpay.providers.mock")
public record MockProviderProperties(String baseUrl, String webhookSecret) {
}
