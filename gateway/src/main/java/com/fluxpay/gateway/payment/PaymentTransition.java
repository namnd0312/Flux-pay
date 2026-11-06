package com.fluxpay.gateway.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * One immutable row per status change: the audit ledger. {@code fromStatus} is null for
 * the first row of a payment. {@code reasonCode} is a stable token, never free text.
 */
@Entity
@Table(name = "payment_transitions")
public class PaymentTransition implements Persistable<UUID> {

    @Id
    private UUID id;

    /** See {@code Payment.isNew}: forces persist for assigned-UUID rows. */
    @Transient
    private boolean isNew = true;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PaymentStatus toStatus;

    @Column(name = "reason_code", nullable = false)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransitionSource source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentTransition() {
        // for JPA
    }

    public PaymentTransition(UUID id, UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
            String reasonCode, TransitionSource source, Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reasonCode = reasonCode;
        this.source = source;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public TransitionSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
