package com.fluxpay.gateway.webhook;

/** Inbound webhook signature did not verify. Maps to HTTP 401. Thrown before any write. */
public class WebhookBadSignatureException extends RuntimeException {
    public WebhookBadSignatureException() {
        super("Webhook signature verification failed");
    }
}
