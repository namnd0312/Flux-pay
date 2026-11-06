package com.fluxpay.gateway.payment;

/** Who caused a transition. Recorded on every audit row so the ledger explains itself. */
public enum TransitionSource {
    MERCHANT,
    PROVIDER,
    SYSTEM
}
