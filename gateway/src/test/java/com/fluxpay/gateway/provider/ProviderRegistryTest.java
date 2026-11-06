package com.fluxpay.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The registry resolves by code, exposes the default, and rejects duplicate codes. */
class ProviderRegistryTest {

    private PaymentProvider provider(String code) {
        return new PaymentProvider() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public ProviderPaymentResult createPayment(ProviderPaymentRequest request) {
                return new ProviderPaymentResult("ref", "url");
            }

            @Override
            public boolean verifyWebhookSignature(byte[] rawBody, java.util.Map<String, String> headers) {
                return true;
            }

            @Override
            public ProviderWebhookEvent parseWebhook(byte[] rawBody) {
                return null;
            }
        };
    }

    @Test
    void resolvesByCodeAndDefault() {
        var registry = new ProviderRegistry(List.of(provider("MOCK"), provider("VNPAY")), "MOCK");
        assertThat(registry.get("VNPAY").code()).isEqualTo("VNPAY");
        assertThat(registry.defaultCode()).isEqualTo("MOCK");
    }

    @Test
    void unknownCodeThrows() {
        var registry = new ProviderRegistry(List.of(provider("MOCK")), "MOCK");
        assertThatThrownBy(() -> registry.get("NOPE")).isInstanceOf(UnknownProviderException.class);
    }

    @Test
    void duplicateCodeFailsFast() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(provider("MOCK"), provider("MOCK")), "MOCK"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate provider code");
    }
}
