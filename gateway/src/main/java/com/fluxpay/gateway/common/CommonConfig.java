package com.fluxpay.gateway.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-cutting beans shared by every module.
 *
 * <p>The {@link Clock} is injected wherever the gateway reads "now" (payment
 * expiry, event timestamps). Production uses {@link Clock#systemUTC()}; tests
 * from phase 2 replace this bean with a mutable clock so they can drive a
 * payment past its deadline without sleeping.
 */
@Configuration
public class CommonConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
