package com.fluxpay.gateway.merchant;

/**
 * Thrown when the X-Merchant-Id header is missing, or names a merchant that does not
 * exist or is inactive. Mapped to HTTP 401. In phase 2 identity is only looked up, not
 * cryptographically verified; phase 5 replaces this with an HMAC-signed filter.
 */
public class UnknownMerchantException extends RuntimeException {

    public UnknownMerchantException(String message) {
        super(message);
    }
}
