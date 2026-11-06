package com.fluxpay.mockprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the fake provider (port 8081).
 *
 * <p>Stands in for a real acquirer like VNPAY so the whole pay flow runs
 * locally. Increment 1 ships only the skeleton; the hosted pay page and the
 * webhook callback (with its delay/duplicate knobs) arrive in phases 3-4.
 */
@SpringBootApplication
public class MockProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockProviderApplication.class, args);
    }
}
