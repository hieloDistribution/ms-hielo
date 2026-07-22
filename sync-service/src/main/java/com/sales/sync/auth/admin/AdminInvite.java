package com.sales.sync.auth.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per issued admin/repartidor invite. The {@code token_hash}
 * is the SHA-256-hashed token string; the cleartext is returned only
 * at issue time and is never persisted.
 *
 * <p>State machine: {@code used_at IS NULL AND revoked_at IS NULL AND
 * expires_at > now()} means "pending and redeemable". Used invites
 * have {@code used_at} set; revoked invites have {@code revoked_at}
 * set. The redeem flow rejects with {@code invite_revoked} when
 * {@code revoked_at} is non-null.
 *
 * <p>Added in V9: {@code revoked_at} so the admin console can cancel
 * a pending invite.
 */
@Entity
@Table(name = "admin_invites")
public class AdminInvite {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "token_hash", nullable = false, length = 72, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isPending() {
        return usedAt == null
                && revokedAt == null
                && expiresAt.isAfter(Instant.now());
    }

    public boolean isRevoked() { return revokedAt != null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
