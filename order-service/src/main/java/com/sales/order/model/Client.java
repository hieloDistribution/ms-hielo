package com.sales.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical {@code Client} record — a business customer serviced by one or more
 * Vendors. Persisted to {@code order_db.clients} via the V1 Flyway migration.
 *
 * <p>Identity: {@code tax_id} is unique across non-soft-deleted Clients
 * (enforced by the V1 migration's {@code UNIQUE} constraint plus a partial
 * manual filter on {@code deleted_at IS NULL}). Lifecycle:
 * <ul>
 *   <li>{@code active} — business-active flag (default {@code true});</li>
 *   <li>{@code deleted_at} — soft-delete instant; {@code null} = not deleted.</li>
 * </ul>
 *
 * <p>Soft-delete is manual via {@link #softDelete()} — no {@code @SQLDelete}/
 * {@code @Where} (D-09), so opt-out queries like
 * {@code findByIdIncludingDeleted} remain straightforward.
 *
 * <p>IDs are minted in {@link #onCreate} ({@link UUID#randomUUID()}) per D-10.
 */
@Entity
@Table(name = "clients")
public class Client {

    @Id
    @Column(name = "id")
    private UUID id;

    @NotBlank
    @Size(max = 255)
    @Column(name = "name", nullable = false)
    private String name;

    @NotBlank
    @Size(max = 50)
    @Column(name = "tax_id", nullable = false, unique = true)
    private String taxId;

    @NotBlank
    @Size(max = 500)
    @Column(name = "address", nullable = false)
    private String address;

    @NotBlank
    @Size(max = 50)
    @Column(name = "phone", nullable = false)
    private String phone;

    @Email
    @Size(max = 320)
    @Column(name = "email", length = 320)
    private String email;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @DecimalMin(value = "-90")
    @DecimalMax(value = "90")
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @DecimalMin(value = "-180")
    @DecimalMax(value = "180")
    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "user_id")
    private UUID userId;

    public Client() {
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
        if (active == null) {
            active = Boolean.TRUE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Soft-delete the Client. Idempotent — calling this on an already-soft-
     * deleted Client is a no-op and returns the original {@code deleted_at}.
     *
     * @return the (possibly pre-existing) instant stored as {@code deleted_at}
     */
    public Instant softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
        return deletedAt;
    }

    /**
     * Clear the soft-delete marker.
     */
    public void restore() {
        deletedAt = null;
    }

    // --- accessors -----------------------------------------------------

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Client client)) return false;
        return Objects.equals(id, client.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}