package com.fluxpay.gateway.risk;

/** A risk rule refused the payment. Thrown before any row is written or any provider is called. */
public class RiskRejectedException extends RuntimeException {

    private final String reasonCode;

    public RiskRejectedException(String reasonCode) {
        super("Payment rejected by a risk rule: " + reasonCode);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
