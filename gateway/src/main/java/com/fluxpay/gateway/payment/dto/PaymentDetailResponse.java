package com.fluxpay.gateway.payment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fluxpay.gateway.payment.Payment;
import com.fluxpay.gateway.payment.PaymentStatus;
import com.fluxpay.gateway.payment.PaymentTransition;

/** GET response: the payment plus its full transition ledger (audit trail). */
public record PaymentDetailResponse(
        UUID paymentId,
        String orderId,
        long amount,
        PaymentStatus status,
        String payUrl,
        Instant expiresAt,
        List<TransitionView> transitions) {

    public static PaymentDetailResponse from(Payment p, List<PaymentTransition> ts) {
        return new PaymentDetailResponse(p.getId(), p.getOrderId(), p.getAmount(), p.getStatus(),
                p.getPayUrl(), p.getExpiresAt(), ts.stream().map(TransitionView::from).toList());
    }

    public record TransitionView(PaymentStatus from, PaymentStatus to, String reasonCode, String source, Instant at) {
        static TransitionView from(PaymentTransition t) {
            return new TransitionView(t.getFromStatus(), t.getToStatus(), t.getReasonCode(),
                    t.getSource().name(), t.getCreatedAt());
        }
    }
}
