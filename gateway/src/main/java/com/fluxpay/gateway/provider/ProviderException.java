package com.fluxpay.gateway.provider;

/**
 * A provider call failed (transport error, 5xx, timeout). Carries a stable reason code
 * for the problem-detail body; the original cause/message is logged, never leaked to the
 * merchant. Maps to HTTP 502.
 */
public class ProviderException extends RuntimeException {

    private final String reasonCode;

    public ProviderException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
