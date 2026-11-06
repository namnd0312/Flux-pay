package com.fluxpay.gateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;

/**
 * The only place HTTP is actually stubbed. Verifies the adapter calls the provider's
 * session endpoint and maps the response, and that a transport error becomes a
 * ProviderException rather than leaking out.
 */
@RestClientTest(MockProviderAdapter.class)
@Import(HmacSigner.class)
@TestPropertySource(properties = {
        "fluxpay.providers.mock.base-url=http://provider.test",
        "fluxpay.providers.mock.webhook-secret=unit-secret"})
class MockProviderAdapterTest {

    @Autowired
    MockProviderAdapter adapter;

    @Autowired
    MockRestServiceServer server;

    private ProviderPaymentRequest request() {
        return new ProviderPaymentRequest(UUID.randomUUID(), 1000000L, "d", "https://m/r");
    }

    @Test
    void mapsSuccessfulResponse() {
        server.expect(requestTo("http://provider.test/mock/v1/payments"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"providerRef\":\"mp_1\",\"payUrl\":\"http://pay/mp_1\"}",
                        MediaType.APPLICATION_JSON));

        ProviderPaymentResult result = adapter.createPayment(request());

        assertThat(result.providerRef()).isEqualTo("mp_1");
        assertThat(result.payUrl()).isEqualTo("http://pay/mp_1");
        server.verify();
    }

    @Test
    void transportErrorBecomesProviderException() {
        server.expect(requestTo("http://provider.test/mock/v1/payments")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.createPayment(request()))
                .isInstanceOf(ProviderException.class)
                .extracting("reasonCode").isEqualTo("PROVIDER_UNAVAILABLE");
    }
}
