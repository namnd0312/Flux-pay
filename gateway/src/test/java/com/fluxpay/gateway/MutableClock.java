package com.fluxpay.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A Clock whose "now" the tests control, replacing the production systemUTC clock. Lets a
 * test push a payment past its expiry without sleeping. Registered via
 * {@link AbstractIntegrationTest}; reset in each test's {@code @AfterEach}.
 */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    public void setInstant(Instant instant) {
        this.now = instant;
    }

    public void advance(java.time.Duration by) {
        this.now = this.now.plus(by);
    }

    public void reset() {
        this.now = Instant.parse("2026-01-01T00:00:00Z");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
