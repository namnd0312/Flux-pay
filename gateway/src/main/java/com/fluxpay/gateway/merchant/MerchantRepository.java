package com.fluxpay.gateway.merchant;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Loads a merchant by its public api key for request authentication. The filter reads the
 * row (ciphertext secret + active flag) once per request; there is no caching yet because
 * correctness, not latency, is the increment-1 goal.
 */
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByApiKey(String apiKey);
}
