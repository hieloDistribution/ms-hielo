package com.sales.sync.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    public enum Role {
        admin, vendedor, repartidor, cliente
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

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.repartidor;

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
        if (role == null) role = Role.repartidor;
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
    public void setRole(Role role) { this.role = role; }

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