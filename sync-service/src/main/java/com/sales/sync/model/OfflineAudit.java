package com.sales.sync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code OfflineAudit} — Entity to record offline disconnected audits
 * for delivery drivers.
 */
@Entity
@Table(name = "offline_audits")
public class OfflineAudit {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "disconnected_at", nullable = false)
    private Instant disconnectedAt;

    @Column(name = "reconnected_at", nullable = false)
    private Instant reconnectedAt;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    public OfflineAudit() {
    }

    public OfflineAudit(UUID driverId, Instant disconnectedAt, Instant reconnectedAt, Integer durationMinutes) {
        this.driverId = driverId;
        this.disconnectedAt = disconnectedAt;
        this.reconnectedAt = reconnectedAt;
        this.durationMinutes = durationMinutes;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public void setDriverId(UUID driverId) {
        this.driverId = driverId;
    }

    public Instant getDisconnectedAt() {
        return disconnectedAt;
    }

    public void setDisconnectedAt(Instant disconnectedAt) {
        this.disconnectedAt = disconnectedAt;
    }

    public Instant getReconnectedAt() {
        return reconnectedAt;
    }

    public void setReconnectedAt(Instant reconnectedAt) {
        this.reconnectedAt = reconnectedAt;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }
}
