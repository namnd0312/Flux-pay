package com.fluxpay.gateway.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fluxpay.gateway.idempotency.IdempotencyStore.Row;

/**
 * Decides, per (merchant, idempotency key), whether a POST create should run fresh, replay a
 * stored response, be told another copy is in progress, or be rejected for key reuse. All
 * arbitration is in the database — the composite primary key and a conditional lease update —
 * never an in-memory lock, so it is correct across threads and (later) across instances.
 *
 * <p>{@link #begin} is a short {@code @Transactional} method called from the non-transactional
 * {@link IdempotencyFilter}; it must not span the controller/provider call. The filter later
 * calls {@link #complete} or {@link #abandon} once the response status is known.
 */
@Service
public class IdempotencyService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final IdempotencyStore store;
    private final Clock clock;
    private final Duration ttl;
    private final Duration lease;

    public IdempotencyService(IdempotencyStore store, Clock clock,
            @Value("${fluxpay.idempotency.ttl}") Duration ttl,
            @Value("${fluxpay.idempotency.lease}") Duration lease) {
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
        this.lease = lease;
    }

    @Transactional
    public IdempotencyOutcome begin(UUID merchantId, String key, String requestHash) {
        Instant now = clock.instant();
        if (store.claim(merchantId, key, requestHash, now.plus(lease), now, now.plus(ttl))) {
            return IdempotencyOutcome.ofNew(); // first to claim: run the request
        }
        // Lost the claim: a row already exists. Read it and decide.
        Row row = store.find(merchantId, key).orElse(null);
        if (row == null) {
            // Extremely narrow race: the row was purged between claim and read. Treat as
            // in-progress so the caller retries rather than double-running.
            return IdempotencyOutcome.inProgress();
        }
        if (!row.requestHash().equals(requestHash)) {
            return IdempotencyOutcome.mismatch(); // same key, different body -> 422
        }
        if (COMPLETED.equals(row.status())) {
            return IdempotencyOutcome.replay(row.responseStatus(), row.responseBody());
        }
        // IN_PROGRESS: fresh lease means another worker is running it; expired lease means
        // that worker is presumed dead and this request may take over.
        if (row.leaseUntil() != null && row.leaseUntil().isAfter(now)) {
            return IdempotencyOutcome.inProgress(); // -> 409
        }
        if (store.takeOverExpiredLease(merchantId, key, now.plus(lease), now)) {
            return IdempotencyOutcome.ofNew(); // won the take-over: run it
        }
        return IdempotencyOutcome.inProgress(); // another request took over first
    }

    @Transactional
    public void complete(UUID merchantId, String key, int responseStatus, String responseBody) {
        store.complete(merchantId, key, responseStatus, responseBody);
    }

    @Transactional
    public void abandon(UUID merchantId, String key) {
        store.delete(merchantId, key);
    }

    @Transactional
    public int purgeExpired() {
        return store.deleteExpired(clock.instant());
    }
}
