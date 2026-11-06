package com.fluxpay.gateway;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;

/**
 * Test double for the "MOCK" provider used by every gateway integration test, replacing
 * HTTP stubbing. It:
 * <ul>
 *   <li>counts calls, so a test can assert exactly one provider call happened;</li>
 *   <li>asserts no DB transaction is active when it runs, pinning the rule that the
 *       provider call must happen outside a transaction;</li>
 *   <li>can be told to fail, to exercise the compensation path.</li>
 * </ul>
 * Registered as {@code @Primary} with code "MOCK" under the test profile, where the real
 * adapter is excluded, so there is no duplicate-code clash in the registry.
 */
public class CountingFakeProvider implements PaymentProvider {

    private final AtomicInteger calls = new AtomicInteger();
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
