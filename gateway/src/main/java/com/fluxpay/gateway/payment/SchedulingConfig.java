package com.fluxpay.gateway.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler only when {@code fluxpay.scheduling.enabled} is true
 * (the default). The test profile sets it false so the expiry sweeper never fires on its
 * own during tests; tests call {@code orchestrator.expireOverdue()} directly instead.
 */
@Configuration
@ConditionalOnProperty(name = "fluxpay.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig {
}
