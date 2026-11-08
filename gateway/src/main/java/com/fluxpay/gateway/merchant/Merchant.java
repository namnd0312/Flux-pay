package com.fluxpay.gateway.merchant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An API client of the gateway. {@code apiKey} is the public identifier sent in
 * {@code X-Merchant-Id}; {@code secret} holds the HMAC signing secret <em>encrypted at
 * rest</em> ({@code v1:} prefix, see {@link SecretCipher}). Phase 5 turns this into a JPA
 * entity so the auth filter can load the ciphertext by api key and decrypt it; the earlier
 * {@code MerchantResolver} that only looked up the id is gone.
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key", nullable = false, unique = true)
    private String apiKey;

    /** Encrypted signing secret: {@code v1:} + base64(iv || ciphertext || tag). */
    @Column(nullable = false)
    private String secret;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // JPA
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getSecret() {
        return secret;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
