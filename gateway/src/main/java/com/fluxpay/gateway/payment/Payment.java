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
import jakarta.persistence.Version;

/**
 * A single attempt to collect money for a merchant's order.
 *
 * <p>The {@code status} setter is intentionally package-private: only
 * {@link PaymentStateMachine} may change it, which keeps every status change going
 * through the one guarded entry point. {@code version} is the optimistic-lock column
 * exercised by increment 2; it exists now so the schema never changes under it.
 */
@Entity
@Table(name = "payments")
public class Payment implements Persistable<UUID> {

    @Id
    private UUID id;

    /**
     * Ids are assigned in Java (UUID), so Spring Data cannot infer newness from a null id
     * and would pick merge (a needless select) over persist. This transient flag, cleared
     * once the row is persisted or loaded, forces persist for freshly built entities and
     * keeps the same managed instance so mutations after save() are tracked.
     */
    @Transient
    private boolean isNew = true;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    /** Minor-unit-free VND amount; VND has no sub-unit, so a plain long is exact. */
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "provider_code")
    private String providerCode;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "pay_url")
    private String payUrl;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(UUID id, UUID merchantId, String orderId, long amount, Instant expiresAt, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.amount = amount;
        this.status = PaymentStatus.CREATED;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Package-private on purpose: only PaymentStateMachine calls this. */
    void setStatus(PaymentStatus status) {
        this.status = status;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public void setPayUrl(String payUrl) {
        this.payUrl = payUrl;
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

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getPayUrl() {
        return payUrl;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
