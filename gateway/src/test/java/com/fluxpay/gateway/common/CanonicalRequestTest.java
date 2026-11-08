package com.fluxpay.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Pins the canonical string layout and the properties it exists for: the empty-body hash is
 * the well-known SHA-256 of the empty input, an absent idempotency key becomes an empty
 * line, and changing the method, path or body changes the signed bytes (which is what
 * defeats cross-endpoint replay and body tampering).
 */
class CanonicalRequestTest {

    private static final String SHA256_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void layoutIsMethodPathTsKeyBodyHash() {
        String canonical = new String(
                CanonicalRequest.build("GET", "/api/v1/payments", "1700000000000", null, new byte[0]),
                StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo("GET\n/api/v1/payments\n1700000000000\n\n" + SHA256_EMPTY);
    }

    @Test
    void presentIdempotencyKeyAppearsOnItsLine() {
        String canonical = new String(
                CanonicalRequest.build("POST", "/api/v1/payments", "1", "idem-123", new byte[0]),
                StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo("POST\n/api/v1/payments\n1\nidem-123\n" + SHA256_EMPTY);
    }

    @Test
    void emptyBodyHashMatchesWellKnownVector() {
        assertThat(CanonicalRequest.sha256Hex(new byte[0])).isEqualTo(SHA256_EMPTY);
    }

    @Test
    void methodPathAndBodyAllChangeTheBytes() {
        byte[] base = CanonicalRequest.build("GET", "/api/v1/payments/1", "1", null, new byte[0]);
        byte[] method = CanonicalRequest.build("POST", "/api/v1/payments/1", "1", null, new byte[0]);
        byte[] path = CanonicalRequest.build("GET", "/api/v1/payments/1/cancel", "1", null, new byte[0]);
        byte[] body = CanonicalRequest.build("GET", "/api/v1/payments/1", "1", null,
                "x".getBytes(StandardCharsets.UTF_8));
        assertThat(method).isNotEqualTo(base);
        assertThat(path).isNotEqualTo(base);
        assertThat(body).isNotEqualTo(base);
    }
}
