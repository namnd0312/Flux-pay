package com.fluxpay.gateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit-tests the real adapter's webhook verify/parse without Spring: a body signed with
 * the configured secret verifies, a tampered signature does not, and the JSON maps to the
 * neutral event shape.
 */
class MockProviderAdapterSignatureTest {

    private static final String SECRET = "sig-secret";

    private final HmacSigner signer = new HmacSigner();
    private final MockProviderAdapter adapter = new MockProviderAdapter(
            RestClient.builder(), new MockProviderProperties("http://unused", SECRET), signer,
            JsonMapper.builder().build());

    @Test
    void verifiesCorrectSignatureAndRejectsTampered() {
        byte[] body = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        String sig = signer.hex(SECRET.getBytes(StandardCharsets.UTF_8), body);

        assertThat(adapter.verifyWebhookSignature(body, Map.of("X-Mock-Signature", sig))).isTrue();
        assertThat(adapter.verifyWebhookSignature(body, Map.of("X-Mock-Signature", "bad"))).isFalse();
        assertThat(adapter.verifyWebhookSignature(body, Map.of())).isFalse();
    }

    @Test
    void parsesWebhookBody() {
        byte[] body = ("{\"eventId\":\"e1\",\"providerRef\":\"mp_1\",\"merchantRef\":\"m1\","
                + "\"status\":\"SUCCESS\",\"amount\":1000,\"occurredAt\":\"2026-01-01T00:00:00Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        ProviderWebhookEvent event = adapter.parseWebhook(body);
        assertThat(event.eventId()).isEqualTo("e1");
        assertThat(event.providerRef()).isEqualTo("mp_1");
        assertThat(event.status()).isEqualTo("SUCCESS");
        assertThat(event.amount()).isEqualTo(1000L);
    }
}
