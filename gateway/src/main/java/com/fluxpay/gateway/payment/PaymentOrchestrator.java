package com.fluxpay.gateway.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the transaction boundaries for the payment lifecycle. Status changes are delegated
 * to {@link PaymentStateMachine}; this class never calls {@code setStatus} directly.
 *
 * <p>In phase 2 there is no provider yet, so {@link #create} inserts CREATED and moves
 * straight to PENDING with a placeholder pay URL. Phase 3 splits create into two
 * transactions around a real provider HTTP call; the state-machine calls stay the same.
 *
 * <p>Two templates are built from the transaction manager: {@code tx} (default) for
 * create and cancel, and {@code newTx} (REQUIRES_NEW) so the expiry sweeper gives each
 * overdue row an independent transaction. Building them here avoids mutating a shared
 * bean's propagation at runtime.
 */
@Service
public class PaymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);

    private final PaymentRepository payments;
    private final PaymentTransitionRepository transitions;
    private final PaymentStateMachine stateMachine;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final Clock clock;
    private final Duration expiry;

    public PaymentOrchestrator(PaymentRepository payments, PaymentTransitionRepository transitions,
            PaymentStateMachine stateMachine, PlatformTransactionManager txManager, Clock clock,
            @Value("${fluxpay.payment.expiry}") Duration expiry) {
        this.payments = payments;
        this.transitions = transitions;
        this.stateMachine = stateMachine;
        this.tx = new TransactionTemplate(txManager);
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.expiry = expiry;
    }

    public Payment create(UUID merchantId, String orderId, long amount) {
        var now = clock.instant();
        var payment = new Payment(UUID.randomUUID(), merchantId, orderId, amount, now.plus(expiry), now);
        return tx.execute(status -> {
            payments.save(payment);
            // No provider in phase 2: accept immediately with a placeholder pay URL.
            stateMachine.transition(payment, PaymentStatus.PENDING, "PROVIDER_PLACEHOLDER", TransitionSource.SYSTEM);
            payment.setPayUrl("pending-provider");
            return payment;
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
        var overdue = payments.findOverdueIds(PaymentStatus.PENDING, clock.instant());
        int expired = 0;
        for (UUID id : overdue) {
            try {
                Boolean done = newTx.execute(status -> expireOne(id));
                if (Boolean.TRUE.equals(done)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to expire payment {}: {}", id, ex.getMessage());
            }
        }
        return expired;
    }

    private boolean expireOne(UUID id) {
        return payments.loadForSweep(id).map(payment -> {
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return false; // already moved on between the id scan and now
            }
            stateMachine.transition(payment, PaymentStatus.EXPIRED, "EXPIRED_BY_SWEEPER", TransitionSource.SYSTEM);
            return true;
        }).orElse(false);
    }
}
