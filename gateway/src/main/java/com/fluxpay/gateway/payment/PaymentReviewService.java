package com.fluxpay.gateway.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.payment.dto.ReviewItemResponse;
import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderRegistry;

/**
 * The operator's side of a held payment: list what is waiting, approve it, or reject it.
 * Lookups here are by payment id with no merchant scope; this class is only reachable through
 * {@code /internal/*}, behind the admin token.
 *
 * <p>{@link #approve} is two steps, in this order:
 * <ol>
 *   <li><b>claim</b>: one short transaction moves UNDER_REVIEW back to CREATED. Only one caller
 *       can commit that move. Everyone else (a second approve, a merchant cancel, the review
 *       timeout, a reject) either lost the version check and re-reads, or arrives later; both
 *       then see a status they may not leave this way and get 409;</li>
 *   <li><b>hand-off</b>: the winner, and only the winner, calls the provider with no
 *       transaction open and finishes exactly like a new payment ({@link PaymentProviderHandoff}).</li>
 * </ol>
 * Calling the provider first and writing the result afterwards would let every concurrent
 * approve open its own provider session before any of them learned it had lost.
 *
 * <p>The rules are not evaluated again at approval: the operator's decision replaces them.
 */
@Service
public class PaymentReviewService {

    /** Upper bound on one listing; there is no paging. */
    static final int LIST_LIMIT = 100;

    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final ProviderRegistry providers;
    private final PaymentProviderHandoff handoff;
    private final OptimisticRetry retry;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration expiry;

    public PaymentReviewService(PaymentRepository payments, PaymentStateMachine stateMachine,
            ProviderRegistry providers, PaymentProviderHandoff handoff, OptimisticRetry retry,
            PlatformTransactionManager txManager, Clock clock, @Value("${fluxpay.payment.expiry}") Duration expiry) {
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.providers = providers;
        this.handoff = handoff;
        this.retry = retry;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.expiry = expiry;
    }

    /** Payments waiting for a decision, oldest first, at most {@link #LIST_LIMIT}. */
    public List<ReviewItemResponse> listUnderReview() {
        return payments.findHeldForReview(PageRequest.of(0, LIST_LIMIT));
    }

    public Payment approve(UUID id) {
        record Claim(Payment payment, PaymentProvider provider) {
        }
        Claim claim = retry.execute("approve", tx, () -> {
            var p = held(id, PaymentStatus.CREATED);
            // Resolved inside the claim: an unknown provider rolls the claim back and the
            // payment stays held, instead of being left in CREATED with nobody to call.
            PaymentProvider provider = providers.get(p.getProviderCode());
            stateMachine.transition(p, PaymentStatus.CREATED, "REVIEW_APPROVED", TransitionSource.OPERATOR);
            // Until now the deadline was the review deadline. From here the customer gets a
            // full payment window, however late in the review the approval came.
            p.setExpiresAt(clock.instant().plus(expiry));
            return new Claim(p, provider);
        });
        Payment claimed = claim.payment();
        return handoff.submit(claim.provider(), claimed.getId(), claimed.getMerchantId(), claimed.getAmount());
    }

    public Payment reject(UUID id) {
        return retry.execute("reject", tx, () -> {
            var p = held(id, PaymentStatus.FAILED);
            stateMachine.transition(p, PaymentStatus.FAILED, "RISK_REVIEW_REJECTED", TransitionSource.OPERATOR);
            return p;
        });
    }

    /**
     * Loads the payment and insists it is still held. The transition table alone is not enough
     * for reject: PENDING -> FAILED is a legal move (a provider failure), so without this check
     * a late reject would fail a payment the customer can already pay.
     */
    private Payment held(UUID id, PaymentStatus target) {
        var p = payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
        if (p.getStatus() != PaymentStatus.UNDER_REVIEW) {
            throw new IllegalPaymentTransitionException(p.getStatus(), target);
        }
        return p;
    }
}
