package com.fluxpay.gateway.merchant;

/**
 * Thrown when a stored merchant secret cannot be decrypted (wrong key, tampered
 * ciphertext, wrong AAD, or malformed {@code v1:} envelope). The auth filter treats this
 * as a 401 {@code AUTH_BAD_SIGNATURE} — never a 500 — and logs the cause at ERROR, so a
 * key-management problem is operationally visible without leaking detail to the merchant.
 */
public class SecretDecryptException extends RuntimeException {

    public SecretDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
