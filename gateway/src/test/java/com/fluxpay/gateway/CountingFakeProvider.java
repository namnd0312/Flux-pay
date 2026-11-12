package com.fluxpay.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private volatile CreateGate gate;

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
        CreateGate parked = gate;
        if (parked != null && parked.tryEnter(request.merchantRef())) {
            parked.awaitRelease();
        }
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

    /**
     * Parks the next {@code createPayment} call inside the provider until the returned gate
     * is released. This freezes the create flow in the window that matters for the
     * early-webhook race: tx1 has committed the CREATED row, the provider "has accepted",
     * but tx2 (provider ref + PENDING) has not run yet.
     */
    public CreateGate blockNextCreate() {
        CreateGate created = new CreateGate();
        this.gate = created;
        return created;
    }

    public void reset() {
        this.calls.set(0);
        this.failTransport = false;
        CreateGate pending = this.gate;
        this.gate = null;
        if (pending != null) {
            pending.release(); // never leave a create parked for the next test class
        }
    }

    /** Handle on one parked create call. */
    public static final class CreateGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile UUID merchantRef;

        private synchronized boolean tryEnter(UUID ref) {
            if (merchantRef != null) {
                return false; // only the first create is parked
            }
            merchantRef = ref;
            entered.countDown();
            return true;
        }

        private void awaitRelease() {
            await(released, "release of the parked create");
        }

        /** Blocks until the create call is inside the provider; returns the gateway payment id. */
        public UUID awaitEntered() {
            await(entered, "the create call to reach the provider");
            return merchantRef;
        }

        /** The provider reference this fake will return for the parked payment. */
        public String providerRef() {
            return "mp_fake_" + merchantRef;
        }

        public void release() {
            released.countDown();
        }

        private static void await(CountDownLatch latch, String what) {
            try {
                if (!latch.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for " + what);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
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
