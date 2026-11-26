package com.fluxpay.gateway.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.idempotency.OrderAlreadyExistsException;
import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderRegistry;
import com.fluxpay.gateway.risk.RiskDecision;
import com.fluxpay.gateway.risk.RiskDecisionStore;
import com.fluxpay.gateway.risk.RiskEngine;
import com.fluxpay.gateway.risk.RiskFacts;
import com.fluxpay.gateway.risk.RiskRejectedException;

/**
 * The merchant-facing payment operations: create, read, cancel. Status changes are delegated
 * to {@link PaymentStateMachine}; this class never calls {@code setStatus} directly, and
 * it never names a concrete provider (it resolves one through {@link ProviderRegistry}).
 *
 * <p>{@link #create} first asks the risk rules ({@link RiskEngine}). A rejection ends the
 * request with only its decision recorded. A review decision inserts the payment and moves it
 * to UNDER_REVIEW in one transaction and stops there: no provider is called until an operator
 * approves it ({@link PaymentReviewService}). An allowed create commits the payment as CREATED
 * together with its risk decision (tx1) and then hands it to the provider through
 * {@link PaymentProviderHandoff}, which makes the HTTP call with no transaction open.
 *
 * <p>Every block that changes an existing payment runs through {@link OptimisticRetry}: two
 * writers can race on one row (webhook vs cancel vs sweeper), and the loser must re-read
 * and try again rather than fail.
 */
@Service
public class PaymentOrchestrator {

    private final PaymentRepository payments;
    private final PaymentTransitionRepository transitions;
    private final PaymentStateMachine stateMachine;
    private final ProviderRegistry providers;
    private final RiskEngine riskEngine;
    private final RiskDecisionStore riskDecisions;
    private final PaymentProviderHandoff handoff;
    private final ZoneId riskZone;
    private final TransactionTemplate tx;
    private final OptimisticRetry retry;
    private final Clock clock;
    private final Duration expiry;
    private final Duration reviewTimeout;

    public PaymentOrchestrator(PaymentRepository payments, PaymentTransitionRepository transitions,
            PaymentStateMachine stateMachine, ProviderRegistry providers, RiskEngine riskEngine,
            RiskDecisionStore riskDecisions, PaymentProviderHandoff handoff,
            PlatformTransactionManager txManager, OptimisticRetry retry, Clock clock,
            @Value("${fluxpay.payment.expiry}") Duration expiry,
            @Value("${fluxpay.risk.zone}") ZoneId riskZone,
            @Value("${fluxpay.risk.review-timeout}") Duration reviewTimeout) {
        this.payments = payments;
        this.transitions = transitions;
        this.stateMachine = stateMachine;
        this.providers = providers;
        this.riskEngine = riskEngine;
        this.riskDecisions = riskDecisions;
        this.handoff = handoff;
        this.riskZone = riskZone;
        this.tx = new TransactionTemplate(txManager);
        this.retry = retry;
        this.clock = clock;
        this.expiry = expiry;
        this.reviewTimeout = reviewTimeout;
    }

    public Payment create(UUID merchantId, String orderId, long amount, String providerCode) {
        // Resolve the provider before writing anything: an unknown code is a 400 with no orphan row.
        String code = (providerCode == null || providerCode.isBlank()) ? providers.defaultCode() : providerCode;
        PaymentProvider provider = providers.get(code);

        // Ask the risk rules before creating anything: a rejection leaves no payment and makes no provider call.
        var now = clock.instant();
        RiskFacts facts = RiskFacts.of(amount, code, merchantId, orderId, now, riskZone);
        RiskDecision decision = riskEngine.evaluate(facts);
        if (decision.outcome() == RiskDecision.Outcome.REJECT) {
            riskDecisions.record(decision, facts, null, now); // one statement, committed on its own
            throw new RiskRejectedException(decision.reasonCode()); // -> 422 RISK_REJECTED
        }

        // A held payment is not payable yet: its deadline is the review deadline.
        boolean held = decision.outcome() == RiskDecision.Outcome.REVIEW;
        var payment = new Payment(UUID.randomUUID(), merchantId, orderId, amount,
                now.plus(held ? reviewTimeout : expiry), now);
        UUID id = payment.getId();

        try {
            // tx1: CREATED committed. saveAndFlush forces the insert now, so a duplicate live
            // order (partial unique index ux_payments_live_order) fails here instead of later.
            // The risk decision is written in the same transaction: a payment always has the
            // decision that let it through, and a rolled-back insert leaves no decision behind.
            tx.executeWithoutResult(status -> {
                payments.saveAndFlush(payment);
                if (held) {
                    // Insert and hold commit together, so the row is never visible as a plain
                    // CREATED payment that the sweeper could mistake for an abandoned create.
                    payment.setProviderCode(code);
                    stateMachine.transition(payment, PaymentStatus.UNDER_REVIEW, decision.reasonCode(),
                            TransitionSource.SYSTEM);
                }
                riskDecisions.record(decision, facts, id, now);
            });
        } catch (DataIntegrityViolationException ex) {
            // Another live payment for (merchant, orderId) already exists; no row was committed.
            throw new OrderAlreadyExistsException(orderId);
        }

        if (held) {
            return payment; // no provider call, no pay URL
        }

        return handoff.submit(provider, id, merchantId, amount);
    }

    public Payment get(UUID id, UUID merchantId) {
        return payments.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    public List<PaymentTransition> transitionsOf(UUID paymentId) {
        return transitions.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    public Payment cancel(UUID id, UUID merchantId) {
        // Losing to a concurrent webhook or sweeper is retried; the re-read then sees a terminal
        // payment and the state machine answers 409 instead of the lock answering 500.
        return retry.execute("cancel", tx, () -> {
            var payment = payments.findByIdAndMerchantId(id, merchantId)
                    .orElseThrow(() -> new PaymentNotFoundException(id));
            stateMachine.transition(payment, PaymentStatus.CANCELLED, "MERCHANT_CANCEL", TransitionSource.MERCHANT);
            return payment;
        });
    }
}
