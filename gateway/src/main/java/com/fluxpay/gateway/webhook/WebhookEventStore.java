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

    public void markApplied(UUID id, UUID paymentId, Instant now) {
        jdbc.sql("update webhook_events set processing_status = 'APPLIED', "
                        + "payment_id = cast(:pid as uuid), processed_at = :now where id = :id")
                .param("pid", idText(paymentId))
                .param("now", at(now))
                .param("id", id)
                .update();
    }

    public void markRejected(UUID id, UUID paymentId, String reason, Instant now) {
        jdbc.sql("update webhook_events set processing_status = 'REJECTED', reject_reason = :r, "
                        + "payment_id = cast(:pid as uuid), processed_at = :now where id = :id")
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
}
