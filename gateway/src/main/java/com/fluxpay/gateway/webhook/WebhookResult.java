package com.fluxpay.gateway.webhook;

/**
 * Outcome of ingesting one webhook, after a valid signature. Always returned with HTTP
 * 200: a business rejection is NOT an HTTP error, otherwise a real provider would retry a
 * webhook that can never succeed. {@code rejectReason} is null when applied or duplicate.
 */
public record WebhookResult(boolean duplicate, boolean applied, String rejectReason) {

    static WebhookResult asDuplicate() {
        return new WebhookResult(true, false, null);
    }

    static WebhookResult asApplied() {
        return new WebhookResult(false, true, null);
    }

    static WebhookResult asRejected(String reason) {
        return new WebhookResult(false, false, reason);
    }
}
