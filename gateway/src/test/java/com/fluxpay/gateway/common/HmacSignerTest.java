package com.fluxpay.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Pins HMAC-SHA256 output against vectors computed independently with
 * {@code openssl dgst -sha256 -hmac}, including a non-ASCII (UTF-8) body, so the signer
 * cannot drift. Also checks constant-time verify accepts the right signature and rejects
 * a wrong or missing one.
 */
class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner();

    @Test
    void matchesOpensslAsciiVector() {
        String hex = signer.hex("k".getBytes(StandardCharsets.UTF_8), "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(hex).isEqualTo("406e4b43f87095aa86ca6299d25e875921fefa180f02043bb29bec5681c0c2d0");
    }

    @Test
    void matchesOpensslNonAsciiVector() {
        String hex = signer.hex("secret".getBytes(StandardCharsets.UTF_8),
                "xin chào".getBytes(StandardCharsets.UTF_8));
        assertThat(hex).isEqualTo("44568c92e12dd11076a732b29fa52807273a7dae09ad9aa8f829eaf14c5a3dfb");
    }

    @Test
    void verifyAcceptsCorrectAndRejectsWrong() {
        byte[] secret = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        String good = signer.hex(secret, data);
        assertThat(signer.verify(secret, data, good)).isTrue();
        assertThat(signer.verify(secret, data, "deadbeef")).isFalse();
        assertThat(signer.verify(secret, data, null)).isFalse();
    }
}
