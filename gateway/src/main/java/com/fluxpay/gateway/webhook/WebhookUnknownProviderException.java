package com.fluxpay.gateway.webhook;

/** Webhook posted to an unknown provider code. Maps to HTTP 404 (distinct from the 400
 * used when a merchant picks an unknown provider on create). */
public class WebhookUnknownProviderException extends RuntimeException {
    public WebhookUnknownProviderException(String code) {
        super("Unknown provider code: " + code);
    }
}
