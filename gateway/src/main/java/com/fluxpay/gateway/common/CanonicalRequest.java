package com.fluxpay.gateway.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the exact byte string a merchant signs (and the gateway re-signs to verify).
 * Binding method, path, timestamp, idempotency key and a hash of the body into one signed
 * payload is what stops two attacks a bare body signature misses:
 * <ul>
 *   <li>replaying a signed request against a different endpoint (e.g. a GET signature
 *       reused on {@code POST .../cancel}) — the method and path differ, so the signature
 *       no longer matches;</li>
 *   <li>tampering with the body after signing — the body hash no longer matches.</li>
 * </ul>
 *
 * <p>Layout (UTF-8 bytes, newline separated, no trailing newline):
 * <pre>
 * METHOD
 * PATH                 request URI, no host, no query
 * TIMESTAMP            epoch millis as sent in X-Timestamp
 * IDEMPOTENCY-KEY      header value, or an empty line when absent
 * sha256hex(body)      lowercase hex; sha256 of the empty input for a bodyless request
 * </pre>
 * Every field except the final hash is ASCII, so the input is charset-stable across the
 * Java gateway and the CryptoJS Postman signer; the body's bytes only enter through their
 * hex digest.
 */
public final class CanonicalRequest {

    private CanonicalRequest() {
    }

    public static byte[] build(String method, String path, String timestamp, String idempotencyKey, byte[] body) {
        String canonical = method + '\n'
                + path + '\n'
                + timestamp + '\n'
                + (idempotencyKey == null ? "" : idempotencyKey) + '\n'
                + sha256Hex(body == null ? new byte[0] : body);
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /** Lowercase hex SHA-256 of raw bytes. */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
