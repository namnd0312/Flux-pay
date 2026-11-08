package com.fluxpay.gateway.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.idempotency.OrderAlreadyExistsException;
import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;
import com.fluxpay.gateway.provider.ProviderRegistry;

/**
 * Owns the transaction boundaries for the payment lifecycle. Status changes are delegated
 * to {@link PaymentStateMachine}; this class never calls {@code setStatus} directly, and
 * it never names a concrete provider (it resolves one through {@link ProviderRegistry}).
 *
 * <p>{@link #create} runs as two transactions around a provider HTTP call that is made
 * with no transaction open, so a pooled DB connection is never held for the length of an
 * HTTP timeout:
 * <ol>
 *   <li>tx1: insert the payment as CREATED and commit;</li>
 *   <li>call the provider (no transaction);</li>
 *   <li>tx2: store the provider ref/pay URL and transition to PENDING.</li>
 * </ol>
 * A provider failure compensates the committed CREATED row to FAILED (502); any other
 * failure compensates to FAILED with an internal reason (500). A JVM death between tx1 and
 * tx2 leaves a CREATED row, which the sweeper later fails via {@link #failAbandonedCreated}.
 *
 * <p>Two templates are built from the transaction manager: {@code tx} (default) for the
 * create/cancel steps, and {@code newTx} (REQUIRES_NEW) so the sweeper gives each row an
 * independent transaction. Building them here avoids mutating a shared bean at runtime.
 */
@Service
public class PaymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);

    private final PaymentRepository payments;
    private final PaymentTransitionRepository transitions;
    private final PaymentStateMachine stateMachine;
    private final ProviderRegistry providers;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final Clock clock;
    private final Duration expiry;
    private final Duration createdTimeout;

    public PaymentOrchestrator(PaymentRepository payments, PaymentTransitionRepository transitions,
            PaymentStateMachine stateMachine, ProviderRegistry providers, PlatformTransactionManager txManager,
            Clock clock, @Value("${fluxpay.payment.expiry}") Duration expiry,
            @Value("${fluxpay.payment.created-timeout}") Duration createdTimeout) {
        this.payments = payments;
        this.transitions = transitions;
        this.stateMachine = stateMachine;
        this.providers = providers;
        this.tx = new TransactionTemplate(txManager);
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.expiry = expiry;
        this.createdTimeout = createdTimeout;
    }

    public Payment create(UUID merchantId, String orderId, long amount, String providerCode) {
        // Resolve the provider before writing anything: an unknown code is a 400 with no orphan row.
        String code = (providerCode == null || providerCode.isBlank()) ? providers.defaultCode() : providerCode;
        PaymentProvider provider = providers.get(code);

        var now = clock.instant();
        var payment = new Payment(UUID.randomUUID(), merchantId, orderId, amount, now.plus(expiry), now);
        UUID id = payment.getId();

        try {
            // tx1: CREATED committed. saveAndFlush forces the insert now, so a duplicate live
            // order (partial unique index ux_payments_live_order) fails here instead of later.
            tx.execute(status -> payments.saveAndFlush(payment));
        } catch (DataIntegrityViolationException ex) {
            // Another live payment for (merchant, orderId) already exists; no row was committed.
            throw new OrderAlreadyExistsException(orderId);
        }

        ProviderPaymentResult result;
        try {
            // No transaction is open here: a slow provider never holds a DB connection.
            result = provider.createPayment(new ProviderPaymentRequest(id, amount, null, null));
        } catch (ProviderException ex) {
            compensateToFailed(id, merchantId, ex.getReasonCode());
            throw ex; // -> 502 PROVIDER_UNAVAILABLE
        } catch (RuntimeException ex) {
            log.error("Unexpected error creating payment {} at provider {}", id, code, ex);
            compensateToFailed(id, merchantId, "INTERNAL_ERROR");
            throw ex; // -> 500
        }

        return tx.execute(status -> { // tx2: record provider result, move to PENDING
            var p = payments.findByIdAndMerchantId(id, merchantId).orElseThrow(() -> new PaymentNotFoundException(id));
            p.setProviderCode(code);
            p.setProviderRef(result.providerRef());
            p.setPayUrl(result.payUrl());
            stateMachine.transition(p, PaymentStatus.PENDING, "PROVIDER_ACCEPTED", TransitionSource.SYSTEM);
            return p;
        });
    }

    private void compensateToFailed(UUID id, UUID merchantId, String reasonCode) {
        tx.execute(status -> {
            var p = payments.findByIdAndMerchantId(id, merchantId)
                    .orElseThrow(() -> new PaymentNotFoundException(id));
            stateMachine.transition(p, PaymentStatus.FAILED, reasonCode, TransitionSource.SYSTEM);
            return p;
        });
    }

    public Payment get(UUID id, UUID merchantId) {
        return payments.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    public List<PaymentTransition> transitionsOf(UUID paymentId) {
        return transitions.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    public Payment cancel(UUID id, UUID merchantId) {
        return tx.execute(status -> {
            var payment = payments.findByIdAndMerchantId(id, merchantId)
                    .orElseThrow(() -> new PaymentNotFoundException(id));
            stateMachine.transition(payment, PaymentStatus.CANCELLED, "MERCHANT_CANCEL", TransitionSource.MERCHANT);
            return payment;
        });
    }

    /**
     * Move every overdue PENDING payment to EXPIRED. Each row runs in its own
     * REQUIRES_NEW transaction and is caught individually, so one poison row cannot block
     * the rest of the batch.
     */
    public int expireOverdue() {
        return sweep(payments.findOverdueIds(PaymentStatus.PENDING, clock.instant()),
                PaymentStatus.PENDING, PaymentStatus.EXPIRED, "EXPIRED_BY_SWEEPER");
    }

    /**
     * Fail CREATED rows older than the created-timeout: these are payments where the
     * create flow died between tx1 and tx2 (e.g. JVM crash), leaving an orphan CREATED row
     * no provider ever accepted. Increment 4 reconciliation can instead query the provider.
     */
    public int failAbandonedCreated() {
        return sweep(payments.findAbandonedCreatedIds(PaymentStatus.CREATED, clock.instant().minus(createdTimeout)),
                PaymentStatus.CREATED, PaymentStatus.FAILED, "CREATE_ABANDONED");
    }

    private int sweep(List<UUID> ids, PaymentStatus expectedFrom, PaymentStatus to, String reasonCode) {
        int moved = 0;
        for (UUID id : ids) {
            try {
                Boolean done = newTx.execute(status -> sweepOne(id, expectedFrom, to, reasonCode));
                if (Boolean.TRUE.equals(done)) {
                    moved++;
                }
            } catch (RuntimeException ex) {
                log.warn("Sweep {} -> {} failed for payment {}: {}", expectedFrom, to, id, ex.getMessage());
            }
        }
        return moved;
    }

    private boolean sweepOne(UUID id, PaymentStatus expectedFrom, PaymentStatus to, String reasonCode) {
        return payments.loadForSweep(id).map(payment -> {
            if (payment.getStatus() != expectedFrom) {
                return false; // already moved on between the id scan and now
            }
            stateMachine.transition(payment, to, reasonCode, TransitionSource.SYSTEM);
            return true;
        }).orElse(false);
    }
}
