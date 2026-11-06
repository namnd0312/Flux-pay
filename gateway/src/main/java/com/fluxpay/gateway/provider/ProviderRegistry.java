package com.fluxpay.gateway.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory that resolves a {@link PaymentProvider} by its code. Built from every provider
 * bean on the classpath; two providers claiming the same code fail the context at startup
 * (a misconfiguration we want to catch immediately, not at request time). The default code
 * comes from config so the payment core never names a concrete provider.
 */
@Component
public class ProviderRegistry {

    private final Map<String, PaymentProvider> byCode;
    private final String defaultCode;

    public ProviderRegistry(List<PaymentProvider> providers,
            @Value("${fluxpay.providers.default}") String defaultCode) {
        Map<String, PaymentProvider> map = new HashMap<>();
        for (PaymentProvider provider : providers) {
            PaymentProvider previous = map.put(provider.code(), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate provider code: " + provider.code());
            }
        }
        this.byCode = map;
        this.defaultCode = defaultCode;
    }

    public PaymentProvider get(String code) {
        PaymentProvider provider = byCode.get(code);
        if (provider == null) {
            throw new UnknownProviderException(code);
        }
        return provider;
    }

    public String defaultCode() {
        return defaultCode;
    }
}
