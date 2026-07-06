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
 * {@code Vendor} — a salesperson mapped to a {@code User} in {@code sync_db}.
 *
 * <p>Persisted to {@code order_db.vendors}. The {@code user_id} column is
 * <strong>NOT</strong> a database FK — {@code sync_db} and {@code order_db}
 * are separate PostgreSQL databases and a cross-DB FK is impossible. The
 * integrity invariant (a Vendor only points at a live User) is enforced
 * service-side by PR-2's {@code SyncAuthClient}. In PR-1 the
 * {@link com.sales.order.repository.VendorRepository} interface is shipped
 * only; no integrity check runs.
 *
 * <p>Soft-delete is manual via {@link #softDelete()} and mirrors {@link Client}.
 */
@Entity
@Table(name = "vendors")
public class Vendor {

    @Id
    @Column(name = "id")
    private UUID id;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @NotBlank
    @Size(max = 255)
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Size(max = 50)
    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Size(max = 50)
    @Column(name = "phone", length = 50)
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

    public Vendor() {
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
     * Soft-delete the Vendor. Idempotent.
     */
    public Instant softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
        return deletedAt;
    }

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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vendor vendor)) return false;
        return Objects.equals(id, vendor.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}