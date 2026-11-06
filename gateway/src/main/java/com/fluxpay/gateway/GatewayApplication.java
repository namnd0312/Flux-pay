package com.fluxpay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Flux-pay gateway (port 8080).
 *
 * <p>The gateway is the modular monolith that owns payment state and the event
 * ledger. Increment 1 wires only the skeleton; payments, provider calls,
 * webhooks, auth, and idempotency arrive in later phases under
 * {@code com.fluxpay.gateway.{merchant,payment,provider,webhook,idempotency}}.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
