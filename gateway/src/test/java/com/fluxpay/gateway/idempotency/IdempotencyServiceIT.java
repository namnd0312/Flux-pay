package com.fluxpay.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.idempotency.IdempotencyOutcome.Kind;

/**
 * Unit-level behaviour of {@link IdempotencyService#begin} against a real Postgres (the
 * logic lives in SQL). Covers the five outcomes including lease take-over and TTL purge.
 * Keyed by an arbitrary merchant UUID — the table has no FK to merchants, so no seeding is
 * needed and each test uses a fresh id for isolation against the shared container.
 */
class IdempotencyServiceIT extends AbstractIntegrationTest {

    @Autowired
    IdempotencyService service;

    @Autowired
    MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void firstCallIsNew() {
        UUID m = UUID.randomUUID();
        assertThat(service.begin(m, "k", "hash").kind()).isEqualTo(Kind.NEW);
    }

    @Test
    void secondCallWhileInProgressIsInProgress() {
        UUID m = UUID.randomUUID();
        service.begin(m, "k", "hash");
        assertThat(service.begin(m, "k", "hash").kind()).isEqualTo(Kind.IN_PROGRESS);
    }

    @Test
    void sameKeyDifferentBodyIsMismatch() {
        UUID m = UUID.randomUUID();
        service.begin(m, "k", "hashA");
        assertThat(service.begin(m, "k", "hashB").kind()).isEqualTo(Kind.MISMATCH);
    }

    @Test
    void completedKeyReplaysStoredResponse() {
        UUID m = UUID.randomUUID();
        service.begin(m, "k", "hash");
        service.complete(m, "k", 201, "{\"paymentId\":\"abc\"}");
        IdempotencyOutcome o = service.begin(m, "k", "hash");
        assertThat(o.kind()).isEqualTo(Kind.REPLAY);
        assertThat(o.responseStatus()).isEqualTo(201);
        assertThat(o.responseBody()).contains("abc");
    }

    @Test
    void expiredLeaseIsTakenOver() {
        UUID m = UUID.randomUUID();
        assertThat(service.begin(m, "k", "hash").kind()).isEqualTo(Kind.NEW);
        assertThat(service.begin(m, "k", "hash").kind()).isEqualTo(Kind.IN_PROGRESS); // fresh lease
        clock.advance(Duration.ofSeconds(61)); // lease is 60s
        assertThat(service.begin(m, "k", "hash").kind()).isEqualTo(Kind.NEW); // dead worker -> take over
    }

    @Test
    void purgeRemovesExpiredKeysAndFreesTheKey() {
        UUID m = UUID.randomUUID();
        service.begin(m, "k", "hash");
        clock.advance(Duration.ofHours(25)); // ttl is 24h
        assertThat(service.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(service.begin(m, "k", "hash").kind()).isEqualTo(Kind.NEW); // row gone -> fresh
    }
}
