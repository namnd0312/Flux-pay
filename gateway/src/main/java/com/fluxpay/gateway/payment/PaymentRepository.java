package com.fluxpay.gateway.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.fluxpay.gateway.payment.dto.ReviewItemResponse;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every merchant-facing read of a payment goes through {@link #findByIdAndMerchantId},
 * never a bare findById: the merchant id is part of the lookup so one merchant can never
 * load another's payment. The exceptions are marked below: the sweeper, the webhook path and
 * the operator's review API, none of which acts on behalf of a merchant.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Webhook path: resolve the payment a provider event refers to (no merchant scope). */
    Optional<Payment> findByProviderCodeAndProviderRef(String providerCode, String providerRef);

    /** System path only (expiry sweeper). Returns ids so each row can expire in its own tx. */
    @Query("select p.id from Payment p where p.status = :status and p.expiresAt < :before")
    List<UUID> findOverdueIds(@Param("status") PaymentStatus status, @Param("before") Instant before);

    /** System path only: load a single payment for the sweeper without merchant scoping. */
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> loadForSweep(@Param("id") UUID id);

    /**
     * System path only: rows that have sat in CREATED since before {@code before}, meaning the
     * hand-off to the provider never finished (crash between the two transactions). The clock
     * is {@code updatedAt}, the time the row entered CREATED, not {@code createdAt}: a payment
     * approved after a long review enters CREATED long after it was created, and must get the
     * same grace period as a new one. For a new payment the two timestamps are equal.
     */
    @Query("select p.id from Payment p where p.status = :status and p.updatedAt < :before")
    List<UUID> findAbandonedCreatedIds(@Param("status") PaymentStatus status, @Param("before") Instant before);

    /**
     * Operator path only: payments waiting for review, oldest first, each with the reason code
     * of the transition that held it. A payment is held at most once, so the join yields one
     * row per payment.
     */
    @Query("""
            select new com.fluxpay.gateway.payment.dto.ReviewItemResponse(
                p.id, p.merchantId, p.orderId, p.amount, p.providerCode, t.reasonCode, p.createdAt, p.expiresAt)
            from Payment p, PaymentTransition t
            where t.paymentId = p.id
              and t.toStatus = com.fluxpay.gateway.payment.PaymentStatus.UNDER_REVIEW
              and p.status = com.fluxpay.gateway.payment.PaymentStatus.UNDER_REVIEW
            order by p.createdAt, p.id
            """)
    List<ReviewItemResponse> findHeldForReview(Pageable page);

    /**
     * System path only (webhook, pessimistic mode): load the payment and take its row lock
     * until the surrounding transaction ends. On PostgreSQL Hibernate emits
     * {@code SELECT ... FOR NO KEY UPDATE}: it blocks every other update or lock of the row
     * but, unlike plain {@code FOR UPDATE}, not the foreign-key check of another
     * transaction inserting a child row. Any other
     * transaction that wants to lock or update this row waits. Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Bound how long this transaction may wait for a row lock, in milliseconds. PostgreSQL's
     * {@code SET LOCAL} cannot take a bind parameter (it is a utility statement), so the
     * function form is used; {@code true} makes it transaction-local. It is a repository
     * query (not JdbcClient) so it is guaranteed to run on the JPA transaction's connection.
     */
    @Query(value = "select set_config('lock_timeout', :millis, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("millis") String millis);
}
