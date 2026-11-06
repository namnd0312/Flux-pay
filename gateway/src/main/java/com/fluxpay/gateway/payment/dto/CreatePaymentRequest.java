package com.fluxpay.gateway.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/payments}. VND amount is a positive long (VND has no
 * sub-unit). {@code provider} selects which provider to route to (defaults to the
 * configured provider when blank). {@code returnUrl} is the browser redirect target, not
 * a server callback.
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 100) String orderId,
        @Positive long amount,
        @Size(max = 500) String description,
        @Size(max = 500) String returnUrl,
        @Size(max = 40) String provider) {
}
