package com.fluxpay.gateway.idempotency;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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
     * Try to claim the key as IN_PROGRESS. Returns true if this caller inserted the row
     * (it is first and should run the request); false if the row already existed (a
     * concurrent or earlier request owns or completed it). ON CONFLICT DO NOTHING means a
     * lost race is a clean no-op, not a unique-violation error.
     */
    public boolean claim(UUID merchantId, String key, String requestHash, Instant leaseUntil,
            Instant now, Instant expiresAt) {
        return jdbc.sql("""
                        insert into idempotency_keys
                            (merchant_id, idempotency_key, request_hash, status, lease_until, created_at, expires_at)
                        values (:m, :k, :h, 'IN_PROGRESS', :lease, :now, :exp)
                        on conflict (merchant_id, idempotency_key) do nothing
                        returning 1
                        """)
                .param("m", merchantId)
                .param("k", key)
                .param("h", requestHash)
                .param("lease", at(leaseUntil))
                .param("now", at(now))
                .param("exp", at(expiresAt))
                .query(Integer.class)
                .optional()
                .isPresent();
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
     * Take over an IN_PROGRESS key whose lease has expired. Returns true if this caller won
     * the conditional update (and may now run the request); false if another caller already
     * took it or it is no longer an expired IN_PROGRESS row.
     */
    public boolean takeOverExpiredLease(UUID merchantId, String key, Instant newLeaseUntil, Instant now) {
        return jdbc.sql("""
                        update idempotency_keys set lease_until = :lease
                        where merchant_id = :m and idempotency_key = :k
                          and status = 'IN_PROGRESS' and lease_until < :now
                        returning 1
                        """)
                .param("lease", at(newLeaseUntil))
                .param("m", merchantId)
                .param("k", key)
                .param("now", at(now))
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public void complete(UUID merchantId, String key, int responseStatus, String responseBody) {
        jdbc.sql("""
                        update idempotency_keys
                        set status = 'COMPLETED', response_status = :s, response_body = :b, lease_until = null
                        where merchant_id = :m and idempotency_key = :k
                        """)
                .param("s", responseStatus)
                .param("b", responseBody)
                .param("m", merchantId)
                .param("k", key)
                .update();
    }

    /** Drop the key so the merchant can retry (used when the response is not storable). */
    public void delete(UUID merchantId, String key) {
        jdbc.sql("delete from idempotency_keys where merchant_id = :m and idempotency_key = :k")
                .param("m", merchantId)
                .param("k", key)
                .update();
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
