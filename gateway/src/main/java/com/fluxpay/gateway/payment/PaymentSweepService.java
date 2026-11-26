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
 * The time-driven status changes: nobody asks for them, they happen because a deadline passed.
 * {@link PaymentExpirySweeper} calls these on a timer; tests call them directly after moving
 * the clock.
 *
 * <p>Each payment is moved in its own REQUIRES_NEW transaction and failures are caught per
 * row, so one bad row cannot block the rest of a sweep. A row that loses a race (webhook or
 * cancel got there first) is simply skipped: it is re-evaluated on the next sweep, which is
 * why these paths do not use {@link OptimisticRetry}.
 */
@Service
public class PaymentSweepService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSweepService.class);

    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final TransactionTemplate newTx;
    private final Clock clock;
    private final Duration createdTimeout;

    public PaymentSweepService(PaymentRepository payments, PaymentStateMachine stateMachine,
            PlatformTransactionManager txManager, Clock clock,
            @Value("${fluxpay.payment.created-timeout}") Duration createdTimeout) {
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.createdTimeout = createdTimeout;
    }

    /**
     * Move every payment whose deadline has passed to EXPIRED: a PENDING payment nobody paid in
     * time, and a payment held for review that no operator decided in time. Both are found by
     * {@code expires_at}; a held payment's deadline is the review timeout.
     */
    public int expireOverdue() {
        var now = clock.instant();
        return sweep(payments.findOverdueIds(PaymentStatus.PENDING, now),
                        PaymentStatus.PENDING, PaymentStatus.EXPIRED, "EXPIRED_BY_SWEEPER")
                + sweep(payments.findOverdueIds(PaymentStatus.UNDER_REVIEW, now),
                        PaymentStatus.UNDER_REVIEW, PaymentStatus.EXPIRED, "REVIEW_TIMEOUT");
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
