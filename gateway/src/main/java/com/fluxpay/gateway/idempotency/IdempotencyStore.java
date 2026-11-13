package com.fluxpay.gateway.idempotency;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Direct SQL access to the idempotency_keys table. Like {@code WebhookEventStore}, it uses
 * JdbcClient rather than JPA because correctness rests on two Postgres features JPA hides:
 * {@code INSERT ... ON CONFLICT DO NOTHING RETURNING} (the composite primary key is the
 * single arbiter of who runs the request first) and a conditional {@code UPDATE ... WHERE
 * lease_until < now RETURNING} (the single arbiter of who takes over a dead worker's lease).
 * No in-memory lock is involved.
 *
 * <p>Every acquisition returns a <b>fencing token</b> ({@code lease_epoch}, drawn from a
 * sequence so it never repeats). {@link #complete} and {@link #deleteOwned} carry that token
 * in their WHERE clause: the ownership check and the write are one statement, so a worker
 * whose lease was taken over while it was stalled cannot write any more.
 *
 * <p>Timestamps bind as OffsetDateTime (UTC): the PostgreSQL driver cannot infer a SQL type
 * for a bare Instant. Each method runs inside the caller's transaction.
 */
@Component
public class IdempotencyStore {

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Try to claim the key as IN_PROGRESS. Returns this caller's fencing token if it
     * inserted the row (it is first and should run the request); empty if the row already
     * existed (a concurrent or earlier request owns or completed it). ON CONFLICT DO NOTHING
     * means a lost race is a clean no-op, not a unique-violation error. The token is the
     * column default, {@code nextval} of the lease sequence.
     */
    public OptionalLong claim(UUID merchantId, String key, String requestHash, Instant leaseUntil,
            Instant now, Instant expiresAt) {
        return jdbc.sql("""
                        insert into idempotency_keys
                            (merchant_id, idempotency_key, request_hash, status, lease_until, created_at, expires_at)
                        values (:m, :k, :h, 'IN_PROGRESS', :lease, :now, :exp)
                        on conflict (merchant_id, idempotency_key) do nothing
                        returning lease_epoch
                        """)
                .param("m", merchantId)
                .param("k", key)
                .param("h", requestHash)
                .param("lease", at(leaseUntil))
                .param("now", at(now))
                .param("exp", at(expiresAt))
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    public Optional<Row> find(UUID merchantId, String key) {
        return jdbc.sql("""
                        select request_hash, status, lease_until, response_status, response_body
                        from idempotency_keys where merchant_id = :m and idempotency_key = :k
                        """)
                .param("m", merchantId)
                .param("k", key)
                .query((rs, n) -> {
                    OffsetDateTime lease = rs.getObject("lease_until", OffsetDateTime.class);
                    Integer status = rs.getObject("response_status", Integer.class);
                    return new Row(
                            rs.getString("request_hash"),
                            rs.getString("status"),
                            lease == null ? null : lease.toInstant(),
                            status,
                            rs.getString("response_body"));
                })
                .optional();
    }

    /**
     * Take over an IN_PROGRESS key whose lease has expired. Returns the new fencing token if
     * this caller won the conditional update (and may now run the request); empty if another
     * caller already took it or it is no longer an expired IN_PROGRESS row. Issuing a new
     * token is what locks the previous owner out.
     */
    public OptionalLong takeOverExpiredLease(UUID merchantId, String key, Instant newLeaseUntil, Instant now) {
        return jdbc.sql("""
                        update idempotency_keys
                        set lease_until = :lease, lease_epoch = nextval('idempotency_lease_epoch_seq')
                        where merchant_id = :m and idempotency_key = :k
                          and status = 'IN_PROGRESS' and lease_until < :now
                        returning lease_epoch
                        """)
                .param("lease", at(newLeaseUntil))
                .param("m", merchantId)
                .param("k", key)
                .param("now", at(now))
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    /**
     * Store the response, but only if the caller still owns the lease. Returns false when
     * the row no longer carries {@code leaseEpoch} (taken over, or gone): nothing was written.
     */
    public boolean complete(UUID merchantId, String key, long leaseEpoch, int responseStatus, String responseBody) {
        return jdbc.sql("""
                        update idempotency_keys
                        set status = 'COMPLETED', response_status = :s, response_body = :b, lease_until = null
                        where merchant_id = :m and idempotency_key = :k
                          and status = 'IN_PROGRESS' and lease_epoch = :e
                        """)
                .param("s", responseStatus)
                .param("b", responseBody)
                .param("m", merchantId)
                .param("k", key)
                .param("e", leaseEpoch)
                .update() == 1;
    }

    /**
     * Drop the key so the merchant can retry (used when the response is not storable), but
     * only if the caller still owns the lease. Returns false when nothing was deleted.
     */
    public boolean deleteOwned(UUID merchantId, String key, long leaseEpoch) {
        return jdbc.sql("""
                        delete from idempotency_keys
                        where merchant_id = :m and idempotency_key = :k
                          and status = 'IN_PROGRESS' and lease_epoch = :e
                        """)
                .param("m", merchantId)
                .param("k", key)
                .param("e", leaseEpoch)
                .update() == 1;
    }

    /** TTL purge: remove expired keys. Returns the number deleted. */
    public int deleteExpired(Instant now) {
        return jdbc.sql("delete from idempotency_keys where expires_at < :now")
                .param("now", at(now))
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A stored key's fields relevant to the begin() decision. */
    public record Row(String requestHash, String status, Instant leaseUntil, Integer responseStatus,
            String responseBody) {
    }
}
