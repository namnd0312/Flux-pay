package com.fluxpay.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test double for the "MOCK" provider used by every gateway integration test, replacing
 * HTTP stubbing. Counts calls, asserts no DB transaction is active during
 * {@code createPayment}, and can be told to fail. It also verifies/parses webhooks with a
 * fixed {@link #TEST_WEBHOOK_SECRET} so a test can sign a body the same way.
 *
 * <p>Registered as {@code @Primary} with code "MOCK" under the test profile, where the
 * real adapter is excluded, so there is no duplicate-code clash in the registry.
 */
public class CountingFakeProvider implements PaymentProvider {

    public static final String TEST_WEBHOOK_SECRET = "test-webhook-secret";

    private final AtomicInteger calls = new AtomicInteger();
    private final HmacSigner signer = new HmacSigner();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private volatile boolean failTransport = false;

    @Override
    public String code() {
        return "MOCK";
    }

    @Override
    public ProviderPaymentResult createPayment(ProviderPaymentRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider call must run outside a DB transaction");
        }
        calls.incrementAndGet();
        if (failTransport) {
            throw new ProviderException("PROVIDER_UNAVAILABLE", "Simulated provider failure", null);
        }
        return new ProviderPaymentResult("mp_fake_" + request.merchantRef(),
                "http://localhost:8081/mock/v1/pay/mp_fake_" + request.merchantRef());
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers) {
        String provided = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("x-mock-signature")) {
                provided = e.getValue();
            }
        }
        return signer.verify(TEST_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), rawBody, provided);
    }

    @Override
    public ProviderWebhookEvent parseWebhook(byte[] rawBody) {
        return objectMapper.readValue(rawBody, ProviderWebhookEvent.class);
    }

    public int calls() {
        return calls.get();
    }

    public void failNextCalls(boolean fail) {
        this.failTransport = fail;
    }

    public void reset() {
        this.calls.set(0);
        this.failTransport = false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        public CountingFakeProvider countingFakeProvider() {
            return new CountingFakeProvider();
        }
    }
}
