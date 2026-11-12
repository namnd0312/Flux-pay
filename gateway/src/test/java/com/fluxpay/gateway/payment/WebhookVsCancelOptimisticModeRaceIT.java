package com.fluxpay.gateway.payment;

import org.springframework.test.context.TestPropertySource;

/**
 * The same webhook-vs-cancel race with {@code fluxpay.webhook.lock-mode=optimistic}: the
 * webhook takes no row lock, so the loser is found by the {@code @Version} check at commit
 * and must be saved by the retry. The invariants are identical in both modes; only the
 * mechanism that keeps them differs.
 */
@TestPropertySource(properties = "fluxpay.webhook.lock-mode=optimistic")
class WebhookVsCancelOptimisticModeRaceIT extends WebhookVsCancelRaceIT {

    @Override
    protected boolean expectsVersionConflicts() {
        return true;
    }
}
