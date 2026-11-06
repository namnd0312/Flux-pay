package com.fluxpay.gateway.payment.dto;

import java.time.Instant;
import java.util.UUID;

import com.fluxpay.gateway.payment.Payment;
import com.fluxpay.gateway.payment.PaymentStatus;

/** 201 response of create: just enough for the merchant to redirect the customer. */
public record CreatePaymentResponse(UUID paymentId, PaymentStatus status, String payUrl, Instant expiresAt) {

    public static CreatePaymentResponse from(Payment p) {
        return new CreatePaymentResponse(p.getId(), p.getStatus(), p.getPayUrl(), p.getExpiresAt());
    }
}
