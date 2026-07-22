package com.sales.sync.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
 * truth and is mapped to the {@code user_roles} join table (V6
 * migration, shape B). Each role is a full {@link Role} entity row
 * in the {@code roles} table — not a string enum — so future role
 * additions are migrations, not Java redeploys.
 *
 * <p>Soft-delete: {@link #active} is the source of truth for whether a
 * user can log in or be touched by admin endpoints. {@link #locked} is
 * a separate flag used by the canonical auth spec for brute-force
 * protection.
 *
 * <p>Optimistic locking: {@link #version} is incremented on every JPA
 * save and checked in concurrent updates to the same row.
 *
 * <p>The legacy single-string column {@link #role} is kept deprecated
 * for the JWT cut-over window (PR3-PR5). It is dropped in V8.
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * Code-side enum of the role names seeded by V6. Used by
     * {@link com.sales.sync.auth.security.JwtService} when constructing
     * the {@code role} JWT claim, and by PR3's RoleGateFilter when
     * mapping roles to Spring Security authorities. The enum is the
     * code-side mirror of the {@link Role#getName()} strings; new roles
     * added via migrations can be referenced through
     * {@code User.Role.valueOf(role.getName())}.
     */
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
     *
     * <p>Marked {@code @Transient} because the {@code role} column was
     * dropped by V8. The field is kept for back-compat with the JWT
     * cut-over window (it is populated in memory by {@link #setRoles}
     * from the first element of the role set) and for callers that still
     * rely on {@link #getRole()}; it is no longer a database column.
     */
    @Deprecated
    @Enumerated(EnumType.STRING)
    @Transient
    private Role role = Role.cliente;

    /**
     * Multi-role source of truth. Mapped to the {@code user_roles} join
     * table (V6 migration, shape B). Always loaded eagerly because the
     * role set is read on every authenticated request.
     *
     * <p>NOTE: the field type uses the FQN
     * {@link com.sales.sync.auth.model.Role} to disambiguate from the
     * nested {@link Role} enum declared above. Without the FQN, the
     * compiler resolves {@code Set<Role>} to {@code Set<User.Role>},
     * which is the legacy single-string enum.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<com.sales.sync.auth.model.Role> roles = new HashSet<>();

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

    /**
     * Return the legacy single-string role. Since the {@code role} column
     * was dropped by V8 and made {@code @Transient}, the field is no
     * longer populated by Hibernate's default field access; the cascade
     * in {@link #setRoles} that used to sync {@code role} from the first
     * element of the set never runs (Hibernate injects the ManyToMany
     * collection by reflection). To keep the JWT cut-over window
     * contract (single-string {@code role} claim), derive the value
     * on-the-fly from the eagerly-loaded {@link #roles} set. If the
     * legacy field was explicitly set somewhere (e.g. tests, admin paths
     * that pre-PR3 still mutate it), honor it.
     */
    public Role getRole() {
        if (role != null && role != Role.cliente) {
            return role;
        }
        if (roles != null && !roles.isEmpty()) {
            try {
                return User.Role.valueOf(
                        roles.iterator().next().getName());
            } catch (IllegalArgumentException ignored) {
                // Future role name not in the User.Role enum — fall
                // through to the default.
            }
        }
        return Role.cliente;
    }
    @Deprecated
    public void setRole(Role role) { this.role = role; }

    public Set<com.sales.sync.auth.model.Role> getRoles() { return roles; }
    public void setRoles(Set<com.sales.sync.auth.model.Role> roles) {
        this.roles = (roles == null) ? new HashSet<>() : new HashSet<>(roles);
        // Keep the legacy single-string column in sync for the JWT cut-over
        // window. Removed in V8. We pick the first role name as the
        // "primary" — JWT carries a single role claim until PR5 cut-over.
        if (!this.roles.isEmpty()) {
            com.sales.sync.auth.model.Role first = this.roles.iterator().next();
            try {
                this.role = User.Role.valueOf(first.getName());
            } catch (IllegalArgumentException ignored) {
                // Role name from DB is not in the User.Role enum (e.g.,
                // a future role added by migration). Leave the legacy
                // column as-is — JwtService uses the multi-role set for
                // claim construction post-PR3 anyway.
            }
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
