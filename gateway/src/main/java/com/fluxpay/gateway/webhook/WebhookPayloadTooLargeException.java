package com.fluxpay.gateway.webhook;

/** Webhook body exceeded the size cap. Maps to HTTP 413. Thrown before any write. */
public class WebhookPayloadTooLargeException extends RuntimeException {
    public WebhookPayloadTooLargeException(int limitBytes) {
        super("Webhook body exceeds " + limitBytes + " bytes");
    }
}
