package com.fluxpay.gateway.webhook;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.payment.IllegalPaymentTransitionException;
import com.fluxpay.gateway.payment.OptimisticRetry;
import com.fluxpay.gateway.payment.Payment;
import com.fluxpay.gateway.payment.PaymentRepository;
import com.fluxpay.gateway.payment.PaymentStateMachine;
import com.fluxpay.gateway.payment.PaymentStatus;
import com.fluxpay.gateway.payment.TransitionSource;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;

/**
 * The payment side of webhook ingestion: find the payment an event refers to, check the
 * event against it, and apply the status transition. The event ledger (dedup, outcome
 * bookkeeping) stays in {@link WebhookIngestionService}.
 *
 * <p><b>Early webhook.</b> The provider reference is stored by the create flow's second
 * transaction. An event can arrive before that commit (a fast provider), and then the
 * lookup by provider reference finds nothing. The event also carries {@code merchantRef},
 * which is our own payment id and exists from the create flow's first transaction, so we
 * can tell "payment not ready yet" (come back later) from "payment unknown" (never). See
 * {@link #decideWithoutProviderRef}.
 *
 * <p><b>Row lock.</b> The transition reads the payment with a locking read
 * ({@code SELECT ... FOR NO KEY UPDATE} on PostgreSQL), so a concurrent writer (cancel,
 * sweeper) queues behind this transaction instead of racing it. A bounded
 * {@code lock_timeout} turns a long wait into "try again later" instead of a stuck request
 * thread. The retry wrapper stays: cancel and the sweeper do not take the row lock, so
 * {@code @Version} is still what catches them. (An optimistic-only variant was measured
 * under load against this one: same correctness and latency, but more retries; it was
 * removed. See docs/learning/09-load-test-and-bottlenecks.md.)
 */
@Component
class WebhookPaymentApplier {

    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final OptimisticRetry retry;
    private final TransactionTemplate tx;
    private final String lockTimeoutMillis;

    WebhookPaymentApplier(PaymentRepository payments, PaymentStateMachine stateMachine, OptimisticRetry retry,
            PlatformTransactionManager txManager,
            @Value("${fluxpay.webhook.lock-timeout}") Duration lockTimeout) {
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.retry = retry;
        this.tx = new TransactionTemplate(txManager);
        if (lockTimeout.toMillis() <= 0) {
            // PostgreSQL reads lock_timeout = 0 as "wait forever", the opposite of the intent.
            throw new IllegalArgumentException("fluxpay.webhook.lock-timeout must be at least 1ms");
        }
        this.lockTimeoutMillis = Long.toString(lockTimeout.toMillis());
    }

    WebhookOutcome apply(String providerCode, ProviderWebhookEvent event) {
        if (isBlank(event.providerRef()) || isBlank(event.merchantRef()) || !isKnownStatus(event.status())) {
            // Correctly signed but unusable. Never guess: a missing reference would match every
            // payment that has none yet, and an unknown status must not be read as "failed".
            return WebhookOutcome.rejected(null, "MALFORMED_EVENT");
        }
        Payment payment;
        Optional<Payment> byProviderRef = payments.findByProviderCodeAndProviderRef(providerCode, event.providerRef());
        if (byProviderRef.isPresent()) {
            payment = byProviderRef.get();
        } else {
            // One read by our own id, and every decision below is taken from that single row.
            // Reading twice (by reference, then by id) and combining the answers would be a
            // check-then-act across two snapshots: the create flow can commit in between.
            payment = parseUuid(event.merchantRef()).flatMap(payments::loadForSweep).orElse(null);
            WebhookOutcome decided = decideWithoutProviderRef(payment, providerCode, event);
            if (decided != null) {
                return decided;
            }
        }

        UUID paymentId = payment.getId();
        if (!paymentId.toString().equals(event.merchantRef())) {
            return WebhookOutcome.rejected(paymentId, "REF_MISMATCH");
        }
        if (payment.getAmount() != event.amount()) {
            return WebhookOutcome.rejected(paymentId, "AMOUNT_MISMATCH");
        }
        PaymentStatus target = "SUCCESS".equals(event.status()) ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
        try {
            retry.execute("webhook", tx, () -> {
                Payment p = load(paymentId);
                stateMachine.transition(p, target, "PROVIDER_WEBHOOK", TransitionSource.PROVIDER);
                return null;
            });
            return WebhookOutcome.applied(paymentId);
        } catch (IllegalPaymentTransitionException ex) {
            // The payment is already terminal (or another writer won the race): first terminal
            // state wins, a later event never overwrites it.
            return WebhookOutcome.rejected(paymentId, "ILLEGAL_TRANSITION");
        } catch (PessimisticLockingFailureException ex) {
            return WebhookOutcome.retryLater(paymentId, "LOCK_TIMEOUT");
        } catch (OptimisticLockingFailureException ex) {
            return WebhookOutcome.retryLater(paymentId, "CONCURRENT_UPDATE");
        }
    }

    /**
     * Decides what to do when no payment carries the event's provider reference, from the
     * one row found by {@code merchantRef} (or null). Returns null to continue on the normal
     * path with that row.
     */
    static WebhookOutcome decideWithoutProviderRef(Payment payment, String providerCode, ProviderWebhookEvent event) {
        if (payment == null) {
            return WebhookOutcome.rejected(null, "UNKNOWN_PAYMENT");
        }
        if (payment.getStatus() == PaymentStatus.CREATED) {
            // Create is still between its two transactions. The provider must send this again.
            return WebhookOutcome.retryLater(payment.getId(), "PAYMENT_NOT_READY");
        }
        if (payment.getProviderRef() == null) {
            // Left CREATED without ever recording a provider reference (create failed or was
            // abandoned), yet the provider reports on it. Cannot be applied; keep the link.
            return WebhookOutcome.rejected(payment.getId(), "PAYMENT_NEVER_ACCEPTED");
        }
        if (providerCode.equals(payment.getProviderCode()) && payment.getProviderRef().equals(event.providerRef())) {
            return null; // create committed between the lookup and this read: it is the right payment
        }
        return WebhookOutcome.rejected(payment.getId(), "REF_MISMATCH");
    }

    private Payment load(UUID paymentId) {
        // Transaction-local (third argument true): the setting disappears at commit/rollback,
        // so it cannot leak to whoever borrows this pooled connection next.
        payments.setLocalLockTimeout(lockTimeoutMillis);
        return payments.findByIdForUpdate(paymentId).orElseThrow();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isKnownStatus(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
