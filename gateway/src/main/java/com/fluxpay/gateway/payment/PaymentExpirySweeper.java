package com.fluxpay.gateway.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically moves overdue PENDING payments to EXPIRED through the state machine, so
 * EXPIRED is reachable for the late-webhook demo in phase 4. Gated by the same flag as
 * {@link SchedulingConfig}: the bean does not even exist under the test profile, so tests
 * drive expiry deterministically via {@code orchestrator.expireOverdue()}.
 *
 * <p>This is a deliberate minimal pull-forward from increment 4, which later hardens it
 * (batching, metrics); it is not rebuilt there.
 */
@Component
@ConditionalOnProperty(name = "fluxpay.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class PaymentExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpirySweeper.class);

    private final PaymentOrchestrator orchestrator;

    PaymentExpirySweeper(PaymentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${fluxpay.scheduling.sweep-interval:30s}")
    void sweep() {
        int expired = orchestrator.expireOverdue();
        int abandoned = orchestrator.failAbandonedCreated();
        if (expired > 0 || abandoned > 0) {
            log.info("Sweeper moved {} payment(s) to EXPIRED and failed {} abandoned CREATED row(s)", expired, abandoned);
        }
    }
}
