package com.fluxpay.gateway.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/payments}. VND amount is a positive long (VND has no
 * sub-unit). The {@code provider} selector is added in phase 3; {@code returnUrl} is the
 * browser redirect target, not a server callback.
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 100) String orderId,
        @Positive long amount,
        @Size(max = 500) String description,
        @Size(max = 500) String returnUrl) {
}
