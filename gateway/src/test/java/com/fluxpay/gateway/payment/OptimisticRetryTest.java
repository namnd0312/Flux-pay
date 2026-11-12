package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The retry rule in isolation, with no database: a lost optimistic-lock race is retried in
 * a new transaction, at most {@link OptimisticRetry#MAX_ATTEMPTS} times, and nothing else is.
 */
class OptimisticRetryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OptimisticRetry retry = new OptimisticRetry(meters);
    private final AtomicInteger transactionsOpened = new AtomicInteger();

    /** Stands in for a real template: counts how many transactions the retry opened. */
    private final TransactionTemplate tx = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            transactionsOpened.incrementAndGet();
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    @Test
    void succeedsAfterLosingTwiceEachAttemptInItsOwnTransaction() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute("cancel", tx, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("stale version");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(transactionsOpened).hasValue(3);
        assertThat(retriesCounted("cancel")).isEqualTo(2.0);
    }

    @Test
    void givesUpAfterTheLastAttemptAndRethrowsTheConflict() {
        assertThatThrownBy(() -> retry.execute("webhook", tx, () -> {
            throw new OptimisticLockingFailureException("stale version");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(transactionsOpened).hasValue(OptimisticRetry.MAX_ATTEMPTS);
        // Three attempts = two retries; the final failure is not a retry.
        assertThat(retriesCounted("webhook")).isEqualTo(OptimisticRetry.MAX_ATTEMPTS - 1.0);
    }

    @Test
    void businessRejectionIsNotRetried() {
        assertThatThrownBy(() -> retry.execute("cancel", tx, () -> {
            throw new IllegalPaymentTransitionException(PaymentStatus.SUCCEEDED, PaymentStatus.CANCELLED);
        })).isInstanceOf(IllegalPaymentTransitionException.class);

        assertThat(transactionsOpened).hasValue(1);
        assertThat(meters.find(OptimisticRetry.RETRIES_COUNTER).counter()).isNull();
    }

    private double retriesCounted(String path) {
        return meters.get(OptimisticRetry.RETRIES_COUNTER).tag("path", path).counter().count();
    }
}
