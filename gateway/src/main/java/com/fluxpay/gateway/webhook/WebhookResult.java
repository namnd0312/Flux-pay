package com.fluxpay.gateway.webhook;

/**
 * Outcome of ingesting one webhook, after a valid signature. The HTTP status is the only
 * signal a provider acts on, so the outcome decides it:
 * <ul>
 *   <li>applied, duplicate, or a business rejection that can never succeed -> 200
 *       ("got it, stop sending"); {@code rejectReason} says why it was not applied;</li>
 *   <li>{@code retryLater} -> 503 ("not now, send it again"): the payment is not ready
 *       yet, the row is locked, or another delivery of the same event is still running.</li>
 * </ul>
 * Answering 200 to something that could succeed later would make the provider stop
 * retrying and the event would be lost; answering 5xx to something that can never succeed
 * would make it retry forever.
 */
public record WebhookResult(boolean duplicate, boolean applied, String rejectReason, String retryLater) {

    static WebhookResult asDuplicate() {
        return new WebhookResult(true, false, null, null);
    }

    static WebhookResult asApplied() {
        return new WebhookResult(false, true, null, null);
    }

    static WebhookResult asRejected(String reason) {
        return new WebhookResult(false, false, reason, null);
    }

    /** A redelivery whose body differs from the stored one: acknowledged, never processed. */
    static WebhookResult asDuplicateRejected(String reason) {
        return new WebhookResult(true, false, reason, null);
    }

    static WebhookResult asRetryLater(String reason) {
        return new WebhookResult(false, false, null, reason);
    }

    public boolean shouldRetry() {
        return retryLater != null;
    }
}
