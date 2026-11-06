package com.fluxpay.gateway.provider.mock;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * Adapter translating the gateway's {@link PaymentProvider} contract to the mock
 * provider's HTTP API, and verifying/parsing its webhooks. Transport failures become a
 * {@link ProviderException}; the cause is logged, never returned to the merchant.
 *
 * <p>Webhook signatures are HMAC-SHA256 over the exact raw body bytes, compared against
 * the {@code X-Mock-Signature} header in constant time. Excluded under the {@code test}
 * profile so integration tests use {@code CountingFakeProvider} instead.
 */
@Component
@Profile("!test")
@EnableConfigurationProperties(MockProviderProperties.class)
public class MockProviderAdapter implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProviderAdapter.class);
    private static final String SIGNATURE_HEADER = "x-mock-signature";

    private final RestClient restClient;
    private final HmacSigner signer;
    private final ObjectMapper objectMapper;
    private final byte[] webhookSecret;

    public MockProviderAdapter(RestClient.Builder builder, MockProviderProperties properties,
            HmacSigner signer, ObjectMapper objectMapper) {
        this.restClient = builder.clone().baseUrl(properties.baseUrl()).build();
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.webhookSecret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
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

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers) {
        return signer.verify(webhookSecret, rawBody, signatureHeader(headers));
    }

    @Override
    public ProviderWebhookEvent parseWebhook(byte[] rawBody) {
        return objectMapper.readValue(rawBody, ProviderWebhookEvent.class);
    }

    /** HTTP header names are case-insensitive; find the signature header regardless of casing. */
    private static String signatureHeader(Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(SIGNATURE_HEADER)) {
                return e.getValue();
            }
        }
        return null;
    }

    record SessionRequest(String merchantRef, long amount, String description, String returnUrl) {
    }

    record SessionResponse(String providerRef, String payUrl) {
    }
}
