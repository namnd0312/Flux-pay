package com.fluxpay.gateway.webhook;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Direct SQL access to the webhook_events ledger. Uses JdbcClient rather than JPA because
 * the dedup insert relies on Postgres {@code INSERT ... ON CONFLICT DO NOTHING RETURNING},
 * which is the single arbiter of duplicates. Each method is meant to run inside its own
 * transaction opened by the ingestion service.
 *
 * <p>Timestamps are bound as OffsetDateTime (UTC): the PostgreSQL JDBC driver cannot infer
 * a SQL type for a bare Instant. The nullable payment_id is bound as text and cast to uuid
 * in SQL so a null needs no type hint.
 */
@Component
public class WebhookEventStore {

    private final JdbcClient jdbc;

    public WebhookEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a new RECEIVED row. Returns the new id, or empty if a row already exists for
     * this (providerCode, eventId) -- i.e. a duplicate. No prior exists-check: the unique
     * constraint decides, which is race-safe under concurrent duplicate deliveries.
     */
    public Optional<UUID> insertNew(String providerCode, String eventId, String rawBody, Instant now) {
        UUID id = UUID.randomUUID();
        return jdbc.sql("""
                        insert into webhook_events
                            (id, provider_code, event_id, raw_body, processing_status, duplicate_count, received_at)
                        values (:id, :pc, :eid, :raw, 'RECEIVED', 0, :now)
                        on conflict (provider_code, event_id) do nothing
                        returning id
                        """)
                .param("id", id)
                .param("pc", providerCode)
                .param("eid", eventId)
                .param("raw", rawBody)
                .param("now", at(now))
                .query(UUID.class)
                .optional();
    }

    public void incrementDuplicate(String providerCode, String eventId) {
        jdbc.sql("update webhook_events set duplicate_count = duplicate_count + 1 "
                        + "where provider_code = :pc and event_id = :eid")
                .param("pc", providerCode)
                .param("eid", eventId)
                .update();
    }

    /**
     * Take back an event that an earlier delivery left RETRYABLE, so this redelivery can be
     * processed. One conditional UPDATE is the arbiter: of several concurrent redeliveries
     * exactly one gets the id back. The body must be byte-identical to the stored one, which
     * keeps {@code raw_body} the true record of what was signed and applied.
     */
    public Optional<UUID> reclaimRetryable(String providerCode, String eventId, String rawBody) {
        return jdbc.sql("""
                        update webhook_events
                        set processing_status = 'RECEIVED', reject_reason = null, processed_at = null,
                            duplicate_count = duplicate_count + 1
                        where provider_code = :pc and event_id = :eid
                          and processing_status = 'RETRYABLE' and raw_body = :raw
                        returning id
                        """)
                .param("pc", providerCode)
                .param("eid", eventId)
                .param("raw", rawBody)
                .query(UUID.class)
                .optional();
    }

    /** What is already stored for this event id, and whether the new delivery carries the same bytes. */
    public Optional<Existing> findExisting(String providerCode, String eventId, String rawBody) {
        return jdbc.sql("""
                        select processing_status, (raw_body = :raw) as same_body
                        from webhook_events where provider_code = :pc and event_id = :eid
                        """)
                .param("raw", rawBody)
                .param("pc", providerCode)
                .param("eid", eventId)
                .query((rs, n) -> new Existing(rs.getString("processing_status"), rs.getBoolean("same_body")))
                .optional();
    }

    // The three outcome writers only touch a row that is still RECEIVED, i.e. owned by the
    // delivery that is finishing now. A late writer can never overwrite a newer outcome.

    public void markApplied(UUID id, UUID paymentId, Instant now) {
        jdbc.sql("update webhook_events set processing_status = 'APPLIED', reject_reason = null, "
                        + "payment_id = cast(:pid as uuid), processed_at = :now "
                        + "where id = :id and processing_status = 'RECEIVED'")
                .param("pid", idText(paymentId))
                .param("now", at(now))
                .param("id", id)
                .update();
    }

    public void markRejected(UUID id, UUID paymentId, String reason, Instant now) {
        markOutcome("REJECTED", id, paymentId, reason, now);
    }

    /**
     * The event could not be applied yet, but a later delivery might succeed (payment not
     * ready, lock busy). The HTTP layer answers 503 so the provider sends it again, and
     * {@link #reclaimRetryable} lets that redelivery through the dedup check.
     */
    public void markRetryable(UUID id, UUID paymentId, String reason, Instant now) {
        markOutcome("RETRYABLE", id, paymentId, reason, now);
    }

    private void markOutcome(String status, UUID id, UUID paymentId, String reason, Instant now) {
        // coalesce: an outcome that knows no payment must not erase a link stored earlier.
        jdbc.sql("update webhook_events set processing_status = :st, reject_reason = :r, "
                        + "payment_id = coalesce(cast(:pid as uuid), payment_id), processed_at = :now "
                        + "where id = :id and processing_status = 'RECEIVED'")
                .param("st", status)
                .param("r", reason)
                .param("pid", idText(paymentId))
                .param("now", at(now))
                .param("id", id)
                .update();
    }

    private static OffsetDateTime at(Instant now) {
        return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    private static String idText(UUID id) {
        return id == null ? null : id.toString();
    }

    /** Stored state of an event id, relative to a new delivery of it. */
    public record Existing(String processingStatus, boolean sameBody) {
    }
}
