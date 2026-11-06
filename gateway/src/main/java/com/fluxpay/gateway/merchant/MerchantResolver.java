package com.fluxpay.gateway.merchant;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns the public API key from the {@code X-Merchant-Id} header into the internal
 * merchant UUID, rejecting unknown or inactive merchants.
 *
 * <p>Phase 2 identifies the merchant but does not verify possession of the secret, so a
 * plain lookup is enough; a direct JdbcClient query avoids introducing a JPA entity that
 * phase 5 would have to reshape when it adds the encrypted-secret handling. Phase 5
 * replaces this resolver with an HMAC-verifying servlet filter.
 */
@Component
public class MerchantResolver {

    private final JdbcClient jdbc;

    public MerchantResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new UnknownMerchantException("Missing X-Merchant-Id");
        }
        return jdbc.sql("select id from merchants where api_key = :apiKey and active = true")
                .param("apiKey", apiKey)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new UnknownMerchantException("Unknown or inactive merchant: " + apiKey));
    }
}
