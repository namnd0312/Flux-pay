package com.fluxpay.gateway.payment;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Runs a "read the payment, change it" block again when it loses an optimistic-lock race.
 *
 * <p>{@code Payment.version} ({@code @Version}) makes Hibernate write
 * {@code UPDATE ... WHERE id = ? AND version = ?}. If another transaction committed first,
 * that UPDATE matches no row and Spring raises {@link OptimisticLockingFailureException}:
 * the write was refused, nothing was overwritten. The loser's view of the payment is stale,
 * so the only correct reaction is to start over: a <em>fresh transaction</em> that
 * <em>re-reads</em> the row. That is why {@code work} must load the payment itself, inside
 * the block; an entity captured outside would carry the same stale version into every
 * attempt.
 *
 * <p>Why three attempts is enough: the version only changes together with a status
 * transition, and after losing, a writer's re-read sees the new status. For every writer in
 * the codebase that new status is one it may not leave the way it wanted (a cancel that lost
 * to an approval finds CREATED, a second approval finds CREATED or PENDING, a hand-off that
 * lost to the sweeper finds FAILED), so the state machine gives the business answer (usually
 * {@link IllegalPaymentTransitionException}) on the second attempt. Only the lock conflict is
 * retried; a business rejection is an answer, not a conflict, and passes straight through.
 */
@Component
public class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);

    static final int MAX_ATTEMPTS = 3;
    static final String RETRIES_COUNTER = "fluxpay.payment.optimistic.retries";

    private final MeterRegistry meters;

    public OptimisticRetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * @param path short label of the caller ({@code create}, {@code cancel}, {@code webhook},
     *             {@code compensate}, {@code approve}, {@code reject}); becomes the {@code path} tag of the retry counter
     * @param tx   template that opens one new transaction per attempt
     * @param work the block to run; it must re-read the payment on every call
     * @throws OptimisticLockingFailureException if the last attempt also loses the race
     */
    public <T> T execute(String path, TransactionTemplate tx, Supplier<T> work) {
        for (int attempt = 1;; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (OptimisticLockingFailureException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    // Should be unreachable (see the bound above). If it shows up, a new code
                    // path is changing payments without going through a status transition.
                    log.warn("Optimistic-lock retry exhausted on path {} after {} attempts", path, attempt);
                    throw ex;
                }
                meters.counter(RETRIES_COUNTER, "path", path).increment(); // one per actual retry
            }
        }
    }
}
