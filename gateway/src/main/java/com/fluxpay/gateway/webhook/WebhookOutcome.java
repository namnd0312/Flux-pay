package com.fluxpay.gateway.webhook;

import java.util.UUID;

/**
 * What happened when a verified event met the payment it refers to. Exactly one of three
 * shapes: applied (both reasons null), rejected for good ({@code rejectReason}), or not
 * possible yet ({@code retryReason}). {@code paymentId} is null only when no payment could
 * be linked at all; keeping it on a rejection is what lets reconciliation find, later,
 * the event that proves money moved for a payment the gateway gave up on.
 */
record WebhookOutcome(UUID paymentId, String rejectReason, String retryReason) {

    static WebhookOutcome applied(UUID paymentId) {
        return new WebhookOutcome(paymentId, null, null);
    }

    static WebhookOutcome rejected(UUID paymentId, String reason) {
        return new WebhookOutcome(paymentId, reason, null);
    }

    static WebhookOutcome retryLater(UUID paymentId, String reason) {
        return new WebhookOutcome(paymentId, null, reason);
    }

    boolean isApplied() {
        return rejectReason == null && retryReason == null;
    }

    boolean isRetryLater() {
        return retryReason != null;
    }
}
