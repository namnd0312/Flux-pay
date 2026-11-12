package com.fluxpay.gateway.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderRegistry;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;
import com.fluxpay.gateway.provider.UnknownProviderException;
import com.fluxpay.gateway.webhook.WebhookEventStore.Existing;

/**
 * Ingests one inbound webhook. Deliberately NOT {@code @Transactional}: it runs separate
 * transaction steps because a single transaction cannot both hit the dedup unique
 * constraint and keep processing (a unique violation aborts the whole Postgres
 * transaction, and a runtime exception crossing a transaction boundary marks it
 * rollback-only). The steps:
 * <ol>
 *   <li>verify signature (no tx) -> 401 if bad; parse;</li>
 *   <li>claim the event: insert it (first delivery), or take back a RETRYABLE one
 *       (redelivery), or recognise a duplicate;</li>
 *   <li>validate against the payment and apply the transition ({@link WebhookPaymentApplier});</li>
 *   <li>record the outcome on the event row: APPLIED, REJECTED or RETRYABLE.</li>
 * </ol>
 *
 * <p>A provider delivers at least once and decides whether to retry from the HTTP status
 * alone, so each outcome maps to one of two answers (see {@link WebhookResult}): 200 for
 * anything final, 503 for anything that may succeed on a later delivery. A RETRYABLE row
 * is the ledger side of that 503: it keeps the dedup key from swallowing the redelivery.
 */
@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);

    private final ProviderRegistry providers;
    private final WebhookPaymentApplier applier;
    private final WebhookEventStore events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookIngestionService(ProviderRegistry providers, WebhookPaymentApplier applier,
            WebhookEventStore events, PlatformTransactionManager txManager, Clock clock) {
        this.providers = providers;
        this.applier = applier;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public WebhookResult process(String providerCode, byte[] rawBody, Map<String, String> headers) {
        PaymentProvider provider = resolve(providerCode);

        // 1) signature first, before any write. A bad signature is a 401 and leaves no row.
        if (!provider.verifyWebhookSignature(rawBody, headers)) {
            throw new WebhookBadSignatureException();
        }
        ProviderWebhookEvent event = provider.parseWebhook(rawBody);
        var now = clock.instant();
        String rawString = new String(rawBody, StandardCharsets.UTF_8);

        // 2) claim the event. The unique constraint (insert) and the conditional update
        //    (reclaim) are the only arbiters; no exists-check in application code.
        Optional<UUID> claimed = tx.execute(s -> events.insertNew(providerCode, event.eventId(), rawString, now));
        if (claimed.isEmpty()) {
            claimed = tx.execute(s -> events.reclaimRetryable(providerCode, event.eventId(), rawString));
        }
        if (claimed.isEmpty()) {
            return answerForKnownEvent(providerCode, event.eventId(), rawString);
        }
        UUID id = claimed.get();

        // 3) validate against the payment and apply the transition.
        WebhookOutcome outcome;
        try {
            outcome = applier.apply(providerCode, event);
        } catch (RuntimeException ex) {
            // Unexpected failure: do not leave the row RECEIVED, or every redelivery would be
            // told "in progress" for an attempt that is already dead. Logged here because the
            // generic 500 handler prints no stack trace.
            log.error("Webhook event {} ({}) failed unexpectedly", event.eventId(), providerCode, ex);
            releaseForRedelivery(id);
            throw ex; // -> 500, the provider retries
        }

        // 4) record the outcome on the event row.
        tx.execute(s -> {
            if (outcome.isApplied()) {
                events.markApplied(id, outcome.paymentId(), now);
            } else if (outcome.isRetryLater()) {
                events.markRetryable(id, outcome.paymentId(), outcome.retryReason(), now);
            } else {
                events.markRejected(id, outcome.paymentId(), outcome.rejectReason(), now);
            }
            return null;
        });

        if (outcome.isApplied()) {
            return WebhookResult.asApplied();
        }
        return outcome.isRetryLater()
                ? WebhookResult.asRetryLater(outcome.retryReason())
                : WebhookResult.asRejected(outcome.rejectReason());
    }

    /**
     * The event id is already in the ledger and could not be reclaimed. What we answer
     * depends on what the earlier delivery has (or has not yet) concluded:
     * <ul>
     *   <li>APPLIED / REJECTED: final. 200 duplicate, the provider can stop.</li>
     *   <li>RETRYABLE but with different bytes: not the same event content; acknowledge
     *       and never process it.</li>
     *   <li>RECEIVED (another delivery is running right now), or anything else: its fate is
     *       unknown. 200 here would stop the provider while that delivery may still end
     *       RETRYABLE, so answer "retry later".</li>
     * </ul>
     */
    private WebhookResult answerForKnownEvent(String providerCode, String eventId, String rawString) {
        Optional<Existing> existing = tx.execute(s -> events.findExisting(providerCode, eventId, rawString));
        if (existing.isEmpty()) {
            return WebhookResult.asRetryLater("EVENT_IN_PROGRESS");
        }
        String status = existing.get().processingStatus();
        if ("APPLIED".equals(status) || "REJECTED".equals(status)) {
            countDuplicate(providerCode, eventId);
            return WebhookResult.asDuplicate();
        }
        if ("RETRYABLE".equals(status) && !existing.get().sameBody()) {
            countDuplicate(providerCode, eventId);
            return WebhookResult.asDuplicateRejected("EVENT_BODY_MISMATCH");
        }
        return WebhookResult.asRetryLater("EVENT_IN_PROGRESS");
    }

    private void countDuplicate(String providerCode, String eventId) {
        tx.execute(s -> {
            events.incrementDuplicate(providerCode, eventId);
            return null;
        });
    }

    private void releaseForRedelivery(UUID id) {
        try {
            tx.execute(s -> {
                events.markRetryable(id, null, "INTERNAL_ERROR", clock.instant());
                return null;
            });
        } catch (RuntimeException ex) {
            // Best effort (e.g. the database itself is the failure). The row stays RECEIVED and
            // redeliveries get 503 until the provider gives up; the original error is rethrown.
            log.warn("Could not mark webhook event {} retryable after a failure: {}", id, ex.getMessage());
        }
    }

    private PaymentProvider resolve(String providerCode) {
        try {
            return providers.get(providerCode);
        } catch (UnknownProviderException ex) {
            throw new WebhookUnknownProviderException(providerCode); // 404 for the webhook path
        }
    }
}
