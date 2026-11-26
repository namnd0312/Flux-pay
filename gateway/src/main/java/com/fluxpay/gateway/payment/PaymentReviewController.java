package com.fluxpay.gateway.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fluxpay.gateway.payment.dto.PaymentDetailResponse;
import com.fluxpay.gateway.payment.dto.ReviewItemResponse;

/**
 * Operator API for payments held by a REVIEW rule. Everything under {@code /internal} is
 * guarded by {@code AdminTokenFilter}; merchant credentials do not open it, and these
 * endpoints are not scoped to a merchant.
 *
 * <p>Approve and reject answer 200 with the payment and its ledger. A payment that is no
 * longer held (already decided, cancelled by the merchant, or timed out) answers 409
 * {@code PAYMENT_ILLEGAL_TRANSITION}; an unknown id answers 404. Approve can also answer 502
 * when the provider refuses the payment, which is then FAILED.
 */
@RestController
@RequestMapping("/internal/reviews")
public class PaymentReviewController {

    private final PaymentReviewService reviews;
    private final PaymentOrchestrator orchestrator;

    public PaymentReviewController(PaymentReviewService reviews, PaymentOrchestrator orchestrator) {
        this.reviews = reviews;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public List<ReviewItemResponse> list() {
        return reviews.listUnderReview();
    }

    @PostMapping("/{paymentId}/approve")
    public PaymentDetailResponse approve(@PathVariable UUID paymentId) {
        Payment payment = reviews.approve(paymentId);
        return PaymentDetailResponse.from(payment, orchestrator.transitionsOf(payment.getId()));
    }

    @PostMapping("/{paymentId}/reject")
    public PaymentDetailResponse reject(@PathVariable UUID paymentId) {
        Payment payment = reviews.reject(paymentId);
        return PaymentDetailResponse.from(payment, orchestrator.transitionsOf(payment.getId()));
    }
}
