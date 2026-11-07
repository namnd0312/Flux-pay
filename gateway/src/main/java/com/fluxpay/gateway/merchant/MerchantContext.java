package com.fluxpay.gateway.merchant;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Carries the authenticated merchant from {@link MerchantAuthFilter} to the controller as a
 * request attribute. Only a request that passed signature verification ever has one set, so
 * a controller reading it can trust the identity without re-checking anything. Replaces the
 * phase-2 {@code MerchantResolver}, which the controller called directly with an unverified
 * header.
 *
 * @param merchantId internal UUID of the authenticated merchant
 * @param apiKey     the public api key it authenticated with
 */
public record MerchantContext(UUID merchantId, String apiKey) {

    /** Request-attribute name holding the {@link MerchantContext}. */
    public static final String ATTRIBUTE = "fluxpay.merchant";

    public void storeIn(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, this);
    }

    public static MerchantContext from(HttpServletRequest request) {
        return (MerchantContext) request.getAttribute(ATTRIBUTE);
    }
}
