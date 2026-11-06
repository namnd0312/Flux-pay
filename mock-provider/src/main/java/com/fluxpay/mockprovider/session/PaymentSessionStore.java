package com.fluxpay.mockprovider.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory session store for the fake provider. A ConcurrentHashMap is enough: the mock
 * has no durability requirement, it only needs to behave like a real provider over HTTP.
 */
@Component
public class PaymentSessionStore {

    private final ConcurrentHashMap<String, PaymentSession> sessions = new ConcurrentHashMap<>();

    public void put(PaymentSession session) {
        sessions.put(session.providerRef(), session);
    }

    public Optional<PaymentSession> get(String providerRef) {
        return Optional.ofNullable(sessions.get(providerRef));
    }
}
