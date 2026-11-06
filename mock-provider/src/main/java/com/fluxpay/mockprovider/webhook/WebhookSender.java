package com.fluxpay.mockprovider.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fluxpay.mockprovider.config.MockProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Sends a signed webhook (IPN) back to the gateway after a customer "pays". Runs on the
 * webhook executor so the pay endpoint can answer 202 at once, like a real provider's
 * out-of-band callback. Honours two knobs used by the phase-4 demos:
 * <ul>
 *   <li>{@code delayMs}: wait before sending (simulates a late webhook);</li>
 *   <li>{@code duplicates}: send the same event (same eventId) extra times.</li>
 * </ul>
 * The exact JSON bytes are signed and sent verbatim, so the gateway verifies over the
 * same bytes. A failed send is logged, not retried (retry belongs to increment 2).
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MockProperties properties;

    public WebhookSender(RestClient.Builder builder, ObjectMapper objectMapper, MockProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.gatewayWebhookUrl()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Async("webhookExecutor")
    public void send(String providerRef, String merchantRef, long amount, String status) {
        String eventId = "evt_" + UUID.randomUUID();
        byte[] body = objectMapper.writeValueAsBytes(
                new WebhookPayload(eventId, providerRef, merchantRef, status, amount, Instant.now().toString()));
        String signature = hmacHex(properties.secret().getBytes(StandardCharsets.UTF_8), body);

        if (properties.callback() != null && properties.callback().delayMs() > 0) {
            sleep(properties.callback().delayMs());
        }
        int extra = properties.callback() == null ? 0 : properties.callback().duplicates();
        for (int i = 0; i <= extra; i++) {
            try {
                String response = restClient.post()
                        .uri("/webhooks/MOCK")
                        .header("X-Mock-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                log.info("Webhook {} send {}/{} -> {}", eventId, i + 1, extra + 1, response);
            } catch (RestClientException ex) {
                log.warn("Webhook {} send {}/{} failed: {}", eventId, i + 1, extra + 1, ex.getMessage());
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hmacHex(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC computation failed", ex);
        }
    }

    /** Wire payload the gateway parses into its neutral webhook event shape. */
    record WebhookPayload(String eventId, String providerRef, String merchantRef, String status, long amount,
            String occurredAt) {
    }
}
