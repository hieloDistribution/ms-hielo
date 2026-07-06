package com.sales.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Joint table representing the N:N assignment between a {@link Vendor} and a
 * {@link Client} for a time interval. Persisted to
 * {@code order_db.vendor_client_assignments}.
 *
 * <p>References to {@code vendor_id} / {@code client_id} are stored as plain
 * UUID columns — NO {@code @ManyToOne} / {@code @JoinColumn} (D-12 + parent
 * pre-resolved divergence #2). Cross-joins are written with explicit
 * {@code @Query} clauses in the repository when needed.
 *
 * <p>At-most-one active assignment per {@code (vendor, client)} pair is
 * enforced by a partial unique index in the V1 migration
 * {@code (vendor_id, client_id) WHERE effective_to IS NULL}.
 */
@Entity
@Table(name = "vendor_client_assignments")
public class VendorClientAssignment {

    @Id
    @Column(name = "id")
    private UUID id;

    @NotNull
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @NotNull
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    public VendorClientAssignment() {
    }

    // --- lifecycle -----------------------------------------------------

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // --- domain ops ----------------------------------------------------

    /**
     * Is this assignment currently active (i.e., {@code effective_to IS NULL})?
     */
    public boolean isActive() {
        return effectiveTo == null;
    }

    /**
     * Set the effective start instant.
     */
    public void activateFrom(Instant from) {
        this.effectiveFrom = from;
    }

    /**
     * Mark this assignment as expired at {@code at}. Idempotent enough — the
     * caller is responsible for not un-expiring.
     */
    public void expireAt(Instant at) {
        this.effectiveTo = at;
    }

    // --- accessors -----------------------------------------------------

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public void setVendorId(UUID vendorId) {
        this.vendorId = vendorId;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VendorClientAssignment that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}