package com.fluxpay.gateway.provider.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the mock provider adapter. {@code baseUrl} points at the mock-provider app
 * (localhost in dev, the compose service name in Docker). Bound from
 * {@code fluxpay.providers.mock.base-url}.
 */
@ConfigurationProperties(prefix = "fluxpay.providers.mock")
public record MockProviderProperties(String baseUrl) {
}
