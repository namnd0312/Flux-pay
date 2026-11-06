package com.fluxpay.gateway.provider.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;

/**
 * Adapter translating the gateway's {@link PaymentProvider} contract to the mock
 * provider's HTTP API. Transport failures (connect refused, timeout, 5xx) become a
 * {@link ProviderException} carrying a stable reason code; the underlying cause is logged,
 * never returned to the merchant.
 *
 * <p>Excluded under the {@code test} profile so integration tests use
 * {@code CountingFakeProvider} (also code "MOCK") without a duplicate-code clash; the
 * adapter's own wiring is covered by an isolated {@code @RestClientTest}. HTTP timeouts
 * are applied globally via {@code spring.http.client.*}, so the injected builder's request
 * factory (and the test's mock server) stays intact.
 */
@Component
@Profile("!test")
@EnableConfigurationProperties(MockProviderProperties.class)
public class MockProviderAdapter implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProviderAdapter.class);

    private final RestClient restClient;

    public MockProviderAdapter(RestClient.Builder builder, MockProviderProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.baseUrl()).build();
    }

    @Override
    public String code() {
        return "MOCK";
    }

    @Override
    public ProviderPaymentResult createPayment(ProviderPaymentRequest request) {
        try {
            SessionResponse response = restClient.post()
                    .uri("/mock/v1/payments")
                    .body(new SessionRequest(request.merchantRef().toString(), request.amount(),
                            request.description(), request.returnUrl()))
                    .retrieve()
                    .body(SessionResponse.class);
            if (response == null) {
                throw new ProviderException("PROVIDER_UNAVAILABLE", "Empty response from provider", null);
            }
            return new ProviderPaymentResult(response.providerRef(), response.payUrl());
        } catch (RestClientException ex) {
            log.warn("Mock provider createPayment failed for merchantRef {}: {}",
                    request.merchantRef(), ex.getMessage());
            throw new ProviderException("PROVIDER_UNAVAILABLE", "Provider call failed", ex);
        }
    }

    /** Wire payload to the mock-provider session endpoint. */
    record SessionRequest(String merchantRef, long amount, String description, String returnUrl) {
    }

    /** Wire payload returned by the mock-provider session endpoint. */
    record SessionResponse(String providerRef, String payUrl) {
    }
}
