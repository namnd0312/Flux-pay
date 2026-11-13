package com.fluxpay.gateway.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
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
 * calls {@link #complete} or {@link #abandon} once the response status is known, presenting
 * the fencing token it got from {@code begin}. A lease is a time-limited claim, not a lock:
 * "expired" does not prove the previous owner is dead, only the token proves who owns the
 * key now.
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
        OptionalLong claimed = store.claim(merchantId, key, requestHash, now.plus(lease), now, now.plus(ttl));
        if (claimed.isPresent()) {
            return IdempotencyOutcome.ofNew(claimed.getAsLong()); // first to claim: run the request
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
        OptionalLong takenOver = store.takeOverExpiredLease(merchantId, key, now.plus(lease), now);
        if (takenOver.isPresent()) {
            return IdempotencyOutcome.ofNew(takenOver.getAsLong()); // won the take-over: run it
        }
        return IdempotencyOutcome.inProgress(); // another request took over first
    }

    /**
     * Store the response for replay. {@code leaseEpoch} is the token {@link #begin} handed out
     * with NEW. Returns false if this caller is no longer the lease owner (it stalled past the
     * lease and another request took over): nothing was stored, and nothing must be.
     */
    @Transactional
    public boolean complete(UUID merchantId, String key, long leaseEpoch, int responseStatus, String responseBody) {
        return store.complete(merchantId, key, leaseEpoch, responseStatus, responseBody);
    }

    /** Release the key for a retry. Returns false if this caller no longer owns the lease. */
    @Transactional
    public boolean abandon(UUID merchantId, String key, long leaseEpoch) {
        return store.deleteOwned(merchantId, key, leaseEpoch);
    }

    @Transactional
    public int purgeExpired() {
        return store.deleteExpired(clock.instant());
    }
}
