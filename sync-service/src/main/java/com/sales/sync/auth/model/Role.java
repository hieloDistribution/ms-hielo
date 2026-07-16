package com.sales.sync.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent role row. The {@code roles} table is the source of truth
 * for role names — chosen deliberately as a table (not an enum in
 * code) so future additions (custom role names, per-role metadata
 * like {@code description} / {@code default_permissions}) are migrations
 * or admin endpoints, not Java redeploys.
 *
 * <p>Owner: change {@code admin-console} PR2 (shape B).
 *
 * <p>The {@code name} column is a free-form {@link String}, NOT an
 * enum, so a future migration can introduce a new role without
 * touching Java code. The seed (V6) constrains names to
 * {@code admin}, {@code repartidor}, {@code cliente} via a CHECK
 * constraint, but the entity itself is permissive.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 20, unique = true)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Role other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
