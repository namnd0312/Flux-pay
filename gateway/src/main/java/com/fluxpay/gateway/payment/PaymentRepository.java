package com.fluxpay.gateway.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every merchant-facing read of a payment goes through {@link #findByIdAndMerchantId},
 * never a bare findById: the merchant id is part of the lookup so one merchant can never
 * load another's payment. The sweeper is the only system-level caller and uses the two
 * overdue queries below.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** System path only (expiry sweeper). Returns ids so each row can expire in its own tx. */
    @Query("select p.id from Payment p where p.status = :status and p.expiresAt < :before")
    List<UUID> findOverdueIds(@Param("status") PaymentStatus status, @Param("before") Instant before);

    /** System path only: load a single payment for the sweeper without merchant scoping. */
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> loadForSweep(@Param("id") UUID id);
}
