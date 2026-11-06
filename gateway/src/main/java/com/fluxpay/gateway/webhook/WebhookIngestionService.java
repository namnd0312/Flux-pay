package com.fluxpay.gateway.webhook;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.payment.IllegalPaymentTransitionException;
import com.fluxpay.gateway.payment.Payment;
import com.fluxpay.gateway.payment.PaymentRepository;
import com.fluxpay.gateway.payment.PaymentStateMachine;
import com.fluxpay.gateway.payment.PaymentStatus;
import com.fluxpay.gateway.payment.TransitionSource;
import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderRegistry;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;
import com.fluxpay.gateway.provider.UnknownProviderException;

/**
 * Ingests one inbound webhook. Deliberately NOT {@code @Transactional}: it runs separate
 * transaction steps because a single transaction cannot both hit the dedup unique
 * constraint and keep processing (a unique violation aborts the whole Postgres
 * transaction, and a runtime exception crossing a transaction boundary marks it
 * rollback-only). The steps:
 * <ol>
 *   <li>verify signature (no tx) -> 401 if bad; parse;</li>
 *   <li>insert the event (dedup via ON CONFLICT); a duplicate just bumps the counter;</li>
 *   <li>validate against the payment and apply the transition in its own tx;</li>
 *   <li>mark the event APPLIED or REJECTED.</li>
 * </ol>
 * After a valid signature the HTTP response is always 200, so a real provider never
 * retries a business rejection.
 */
@Service
public class WebhookIngestionService {

    private final ProviderRegistry providers;
    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final WebhookEventStore events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookIngestionService(ProviderRegistry providers, PaymentRepository payments,
            PaymentStateMachine stateMachine, WebhookEventStore events, PlatformTransactionManager txManager,
            Clock clock) {
        this.providers = providers;
        this.payments = payments;
        this.stateMachine = stateMachine;
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
        String rawString = new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);

        // 2) tx A: insert or detect duplicate. The unique constraint is the only arbiter.
        Optional<UUID> rowId = tx.execute(s -> events.insertNew(providerCode, event.eventId(), rawString, now));
        if (rowId.isEmpty()) {
            tx.execute(s -> {
                events.incrementDuplicate(providerCode, event.eventId());
                return null;
            });
            return WebhookResult.asDuplicate();
        }
        UUID id = rowId.get();

        // 3) validate against the payment; apply the transition inside its own tx.
        Validation v = validateAndApply(providerCode, event);

        // 4) tx C: record the outcome on the event row.
        UUID paymentId = v.paymentId();
        tx.execute(s -> {
            if (v.rejectReason() == null) {
                events.markApplied(id, paymentId, now);
            } else {
                events.markRejected(id, paymentId, v.rejectReason(), now);
            }
            return null;
        });

        return v.rejectReason() == null ? WebhookResult.asApplied() : WebhookResult.asRejected(v.rejectReason());
    }

    private PaymentProvider resolve(String providerCode) {
        try {
            return providers.get(providerCode);
        } catch (UnknownProviderException ex) {
            throw new WebhookUnknownProviderException(providerCode); // 404 for the webhook path
        }
    }

    /**
     * Reads the referenced payment and validates it (existence, ref, amount), then applies
     * the status transition in tx B. An illegal transition rolls back only tx B and is
     * reported as a rejection, never overwriting a terminal payment.
     */
    private Validation validateAndApply(String providerCode, ProviderWebhookEvent event) {
        Optional<Payment> found = payments.findByProviderCodeAndProviderRef(providerCode, event.providerRef());
        if (found.isEmpty()) {
            return new Validation(null, "UNKNOWN_PAYMENT");
        }
        Payment payment = found.get();
        UUID paymentId = payment.getId();
        if (!paymentId.toString().equals(event.merchantRef())) {
            return new Validation(paymentId, "REF_MISMATCH");
        }
        if (payment.getAmount() != event.amount()) {
            return new Validation(paymentId, "AMOUNT_MISMATCH");
        }
        PaymentStatus target = "SUCCESS".equals(event.status()) ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
        try {
            tx.execute(s -> {
                Payment p = payments.loadForSweep(paymentId).orElseThrow();
                stateMachine.transition(p, target, "PROVIDER_WEBHOOK", TransitionSource.PROVIDER);
                return null;
            });
            return new Validation(paymentId, null);
        } catch (IllegalPaymentTransitionException ex) {
            return new Validation(paymentId, "ILLEGAL_TRANSITION");
        }
    }

    private record Validation(UUID paymentId, String rejectReason) {
    }
}
