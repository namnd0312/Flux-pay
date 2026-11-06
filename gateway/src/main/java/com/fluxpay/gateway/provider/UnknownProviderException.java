package com.fluxpay.gateway.provider;

/** The requested provider code has no registered adapter. Maps to HTTP 400. */
public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(String code) {
        super("Unknown provider: " + code);
    }
}
