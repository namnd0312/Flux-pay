package com.fluxpay.gateway.merchant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts merchant signing secrets at rest with AES-256-GCM.
 *
 * <p>Envelope format of the stored value:
 * <pre>
 * v1: + base64( iv[12] || ciphertext || gcmTag[16] )
 * </pre>
 * A fresh 12-byte {@link SecureRandom} IV per encryption means two encryptions of the same
 * plaintext never produce the same ciphertext (no equality leak). GCM is authenticated: the
 * tag detects tampering, and the merchant's api key is bound in as <b>AAD</b> so a
 * ciphertext copied onto another merchant's row fails to decrypt instead of silently
 * decrypting to the wrong secret.
 *
 * <p>This is deliberately <em>not</em> production-grade key management: the key is a single
 * symmetric key read from the environment, not a KMS/HSM-backed, rotatable key. The
 * learning note (04) states what a real deployment would add. The constructor fails fast
 * if the key is missing or not exactly 32 bytes, so a misconfigured gateway never starts
 * and later answers 500s mid-flight.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${fluxpay.secret-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "FLUXPAY_SECRET_KEY is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("FLUXPAY_SECRET_KEY is not valid base64", ex);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "FLUXPAY_SECRET_KEY must decode to 32 bytes (AES-256), got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Encrypt {@code plaintext}, binding {@code apiKey} as AAD. Returns the {@code v1:} envelope. */
    public String encrypt(String apiKey, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(apiKey.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException ex) {
            // Encryption failing is a configuration/JVM fault, not a per-request condition.
            throw new IllegalStateException("Secret encryption failed", ex);
        }
    }

    /**
     * Decrypt a stored {@code v1:} envelope, verifying the GCM tag and the {@code apiKey}
     * AAD. Any failure (bad prefix, wrong key, tampered bytes, wrong api key) raises
     * {@link SecretDecryptException}; the plaintext secret bytes are returned so the caller
     * can sign with them.
     */
    public byte[] decrypt(String apiKey, String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new SecretDecryptException("Stored secret is not a v1 envelope", null);
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (blob.length <= IV_BYTES) {
                throw new SecretDecryptException("Ciphertext too short", null);
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(apiKey.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } catch (SecretDecryptException ex) {
            throw ex;
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new SecretDecryptException("Secret decryption failed for api key " + apiKey, ex);
        }
    }
}
