package com.fluxpay.gateway.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecretCipher}. No Spring context: the cipher is constructed with a
 * base64 key directly. They pin the security properties the design relies on — a fresh IV
 * per encryption (no equality leak), the api-key AAD binding, authenticated decryption that
 * rejects the wrong key, and fail-fast construction on a bad key.
 */
class SecretCipherTest {

    private static final String KEY_A = base64Key((byte) 1);
    private static final String KEY_B = base64Key((byte) 2);

    private static String base64Key(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTripReturnsPlaintext() {
        SecretCipher cipher = new SecretCipher(KEY_A);
        String stored = cipher.encrypt("demo-merchant", "demo-secret-change-me");
        assertThat(stored).startsWith("v1:");
        assertThat(new String(cipher.decrypt("demo-merchant", stored), StandardCharsets.UTF_8))
                .isEqualTo("demo-secret-change-me");
    }

    @Test
    void samePlaintextEncryptsDifferentlyEachTime() {
        SecretCipher cipher = new SecretCipher(KEY_A);
        String a = cipher.encrypt("demo-merchant", "same");
        String b = cipher.encrypt("demo-merchant", "same");
        assertThat(a).isNotEqualTo(b); // fresh random IV per encryption
    }

    @Test
    void wrongApiKeyAadFailsToDecrypt() {
        SecretCipher cipher = new SecretCipher(KEY_A);
        String stored = cipher.encrypt("demo-merchant", "s");
        assertThatThrownBy(() -> cipher.decrypt("other-merchant", stored))
                .isInstanceOf(SecretDecryptException.class);
    }

    @Test
    void wrongKeyFailsToDecrypt() {
        String stored = new SecretCipher(KEY_A).encrypt("demo-merchant", "s");
        assertThatThrownBy(() -> new SecretCipher(KEY_B).decrypt("demo-merchant", stored))
                .isInstanceOf(SecretDecryptException.class);
    }

    @Test
    void nonEnvelopeValueIsRejected() {
        SecretCipher cipher = new SecretCipher(KEY_A);
        assertThatThrownBy(() -> cipher.decrypt("demo-merchant", "plaintext-not-v1"))
                .isInstanceOf(SecretDecryptException.class);
    }

    @Test
    void missingOrShortKeyFailsConstruction() {
        assertThatThrownBy(() -> new SecretCipher(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class); // 16 bytes != AES-256
    }
}
