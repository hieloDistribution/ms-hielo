package com.sales.sync.auth.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent user row.
 *
 * <p>Multi-role support: the {@link #roles} collection is the source of
 * truth and is mapped to the {@code user_roles} join table (V6 migration).
 * The legacy single-string {@link #role} column is kept for the JWT
 * cut-over window (PR3 through PR5); it is dropped in V8.
 *
 * <p>Soft-delete: {@link #active} is the source of truth for whether a
 * user can log in or be touched by admin endpoints. {@link #locked} is
 * a separate flag used by the canonical auth spec for brute-force
 * protection.
 *
 * <p>Optimistic locking: {@link #version} is incremented on every JPA
 * save and checked in concurrent updates to the same row.
 */
@Entity
@Table(name = "users")
public class User {

    public enum Role {
        admin, repartidor, cliente
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    /**
     * Legacy single-string role column. Preserved through the JWT cut-over
     * window; dropped in V8. New code MUST write to {@link #roles} instead.
     */
    @Deprecated
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.cliente;

    /**
     * Multi-role source of truth. Mapped to the {@code user_roles} join
     * table via V6. Always loaded eagerly because the role set is read on
     * every authenticated request.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Set<Role> roles = new HashSet<>(Set.of(Role.cliente));

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "dni", length = 50)
    private String dni;

    @Column(name = "business_name", length = 255)
    private String businessName;

    @Column(name = "business_lat", precision = 9, scale = 6)
    private BigDecimal businessLat;

    @Column(name = "business_lng", precision = 9, scale = 6)
    private BigDecimal businessLng;

    @Column(name = "business_address", length = 500)
    private String businessAddress;

    @Column(name = "last_latitude", precision = 9, scale = 6)
    private BigDecimal lastLatitude;

    @Column(name = "last_longitude", precision = 9, scale = 6)
    private BigDecimal lastLongitude;

    @Column(name = "last_location_updated")
    private Instant lastLocationUpdated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (role == null) role = Role.cliente;
        if (roles == null || roles.isEmpty()) {
            roles = new HashSet<>(Set.of(Role.cliente));
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public Role getRole() { return role; }
    @Deprecated
    public void setRole(Role role) { this.role = role; }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) {
        this.roles = (roles == null) ? new HashSet<>() : new HashSet<>(roles);
        // Keep legacy column in sync for the cut-over window. Removed in V8.
        if (!this.roles.isEmpty()) {
            this.role = this.roles.iterator().next();
        }
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getDni() { return dni; }
    public void setDni(String dni) { this.dni = dni; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public BigDecimal getBusinessLat() { return businessLat; }
    public void setBusinessLat(BigDecimal businessLat) { this.businessLat = businessLat; }

    public BigDecimal getBusinessLng() { return businessLng; }
    public void setBusinessLng(BigDecimal businessLng) { this.businessLng = businessLng; }

    public String getBusinessAddress() { return businessAddress; }
    public void setBusinessAddress(String businessAddress) { this.businessAddress = businessAddress; }

    public BigDecimal getLastLatitude() { return lastLatitude; }
    public void setLastLatitude(BigDecimal lastLatitude) { this.lastLatitude = lastLatitude; }

    public BigDecimal getLastLongitude() { return lastLongitude; }
    public void setLastLongitude(BigDecimal lastLongitude) { this.lastLongitude = lastLongitude; }

    public Instant getLastLocationUpdated() { return lastLocationUpdated; }
    public void setLastLocationUpdated(Instant lastLocationUpdated) { this.lastLocationUpdated = lastLocationUpdated; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
