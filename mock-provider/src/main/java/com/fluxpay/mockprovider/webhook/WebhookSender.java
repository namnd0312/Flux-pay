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
import org.springframework.web.client.RestClientResponseException;

import com.fluxpay.mockprovider.config.MockProperties;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends a signed webhook (IPN) back to the gateway after a customer "pays". Runs on the
 * webhook executor so the pay endpoint can answer 202 at once, like a real provider's
 * out-of-band callback.
 *
 * <p>Delivery is <b>at least once</b>: the first delivery is repeated, with a growing
 * pause, until the gateway answers 2xx or the attempts run out. Every attempt carries the
 * same {@code eventId} and the exact same bytes; that is what lets the receiver recognise a
 * redelivery. What is retried follows the usual provider rule:
 * <ul>
 *   <li>5xx, 429, or no answer at all (timeout, connection refused): the receiver may
 *       succeed later, so retry;</li>
 *   <li>any other 4xx (bad signature, unknown path, payload too large): the same request
 *       can never succeed, so stop.</li>
 * </ul>
 * Knobs: {@code delayMs} (late webhook) and {@code duplicates} (extra copies of an event
 * that was already delivered; those are sent once each and not retried).
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private enum Attempt { DELIVERED, RETRY, GIVE_UP }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MockProperties properties;
    private final MeterRegistry meters;

    public WebhookSender(RestClient.Builder builder, ObjectMapper objectMapper, MockProperties properties,
            MeterRegistry meters) {
        this.restClient = builder.clone().baseUrl(properties.gatewayWebhookUrl()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meters = meters;
    }

    @Async("webhookExecutor")
    public void send(String providerRef, String merchantRef, long amount, String status) {
        String eventId = "evt_" + UUID.randomUUID();
        byte[] body = objectMapper.writeValueAsBytes(
                new WebhookPayload(eventId, providerRef, merchantRef, status, amount, Instant.now().toString()));
        String signature = hmacHex(properties.secret().getBytes(StandardCharsets.UTF_8), body);

        sleep(properties.callback().delayMs());
        if (!deliverWithRetry(eventId, body, signature)) {
            return;
        }
        for (int i = 1; i <= properties.callback().duplicates(); i++) {
            Attempt copy = post(eventId, body, signature);
            log.info("Webhook {} duplicate {}/{} -> {}", eventId, i, properties.callback().duplicates(), copy);
        }
    }

    /** Returns true once the gateway has acknowledged the event with a 2xx. */
    private boolean deliverWithRetry(String eventId, byte[] body, String signature) {
        MockProperties.Retry retry = properties.callback().retry();
        long backoff = retry.backoffMs();
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            Attempt result = post(eventId, body, signature);
            log.info("Webhook {} attempt {}/{} -> {}", eventId, attempt, retry.maxAttempts(), result);
            if (result == Attempt.DELIVERED) {
                return true;
            }
            if (result == Attempt.GIVE_UP) {
                return false;
            }
            if (attempt < retry.maxAttempts()) {
                meters.counter("mock.webhook.retried").increment();
                sleep(backoff);
                backoff *= 2;
            }
        }
        meters.counter("mock.webhook.exhausted").increment();
        log.warn("Webhook {} not delivered after {} attempts", eventId, retry.maxAttempts());
        return false;
    }

    private Attempt post(String eventId, byte[] body, String signature) {
        meters.counter("mock.webhook.sent").increment();
        try {
            restClient.post()
                    .uri("/webhooks/MOCK")
                    .header("X-Mock-Signature", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return Attempt.DELIVERED;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            log.warn("Webhook {} answered {}", eventId, status);
            return (status >= 500 || status == 429) ? Attempt.RETRY : Attempt.GIVE_UP;
        } catch (RestClientException ex) {
            log.warn("Webhook {} got no answer: {}", eventId, ex.getMessage());
            return Attempt.RETRY;
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
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
