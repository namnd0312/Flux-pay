package com.fluxpay.mockprovider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for the fake provider. {@code secret} signs outbound webhooks (must match the
 * gateway's provider webhook-secret); {@code gatewayWebhookUrl} is where webhooks are
 * sent; {@code publicBaseUrl} forms the pay URL. The nested groups are knobs that make the
 * mock misbehave on demand, the way real providers do, so the gateway's handling of each
 * case can be demonstrated.
 */
@ConfigurationProperties(prefix = "mock")
public record MockProperties(
        String secret,
        String gatewayWebhookUrl,
        String publicBaseUrl,
        @DefaultValue Callback callback,
        @DefaultValue Create create,
        @DefaultValue SenderPool executor) {

    /**
     * Webhook delivery behaviour.
     *
     * @param delayMs    wait before the first send (a late webhook)
     * @param duplicates extra copies of the same event sent after it was delivered
     * @param autoPay    {@code NONE}, or {@code SUCCESS}/{@code FAILED} to fire the webhook the
     *                   moment a session is created, before the gateway has even received
     *                   the create response (an early webhook)
     */
    public record Callback(
            @DefaultValue("0") long delayMs,
            @DefaultValue("0") int duplicates,
            @DefaultValue("NONE") String autoPay,
            @DefaultValue Retry retry) {

        public Callback {
            // The value is sent to the gateway as the payment outcome, so a typo must fail at
            // startup rather than travel as an unknown status.
            autoPay = autoPay == null ? "NONE" : autoPay.trim().toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("NONE", "SUCCESS", "FAILED").contains(autoPay)) {
                throw new IllegalArgumentException("mock.callback.auto-pay must be NONE, SUCCESS or FAILED");
            }
        }
    }

    /**
     * Redelivery of a webhook the gateway did not accept. Defaults give a window of
     * 0.5 + 1 + 2 + 4 + 8 = 15.5 s, longer than the gateway can spend on its provider call.
     *
     * @param maxAttempts total sends of the first delivery, including the first one
     * @param backoffMs   wait before the second attempt; doubled before each later one
     */
    public record Retry(@DefaultValue("6") int maxAttempts, @DefaultValue("500") long backoffMs) {
    }

    /** @param delayMs hold the create-session response this long (a slow provider) */
    public record Create(@DefaultValue("0") long delayMs) {
    }

    /** Size of the pool that delivers webhooks; a send (with its retries) occupies one thread. */
    public record SenderPool(@DefaultValue("32") int threads, @DefaultValue("2000") int queue) {
    }
}
