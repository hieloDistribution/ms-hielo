package com.sales.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code DeliveryDriver} — representará a los repartidores encargados de la entrega física
 * de los pedidos y su geolocalización en tiempo real.
 */
@Entity
@Table(name = "delivery_drivers")
public class DeliveryDriver {

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

    @Size(max = 100)
    @Column(name = "vehicle_type", length = 100)
    private String vehicleType;

    @Size(max = 50)
    @Column(name = "plate_number", length = 50)
    private String plateNumber;

    @DecimalMin(value = "1.0")
    @DecimalMax(value = "5.0")
    @Column(name = "stars", precision = 3, scale = 2)
    private BigDecimal stars = new BigDecimal("5.0");

    @DecimalMin(value = "-90")
    @DecimalMax(value = "90")
    @Column(name = "last_latitude", precision = 9, scale = 6)
    private BigDecimal lastLatitude;

    @DecimalMin(value = "-180")
    @DecimalMax(value = "180")
    @Column(name = "last_longitude", precision = 9, scale = 6)
    private BigDecimal lastLongitude;

    @Column(name = "last_location_updated")
    private Instant lastLocationUpdated;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DeliveryDriver() {
    }

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
        if (stars == null) {
            stars = new BigDecimal("5.0");
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

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

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public BigDecimal getStars() {
        return stars;
    }

    public void setStars(BigDecimal stars) {
        this.stars = stars;
    }

    public BigDecimal getLastLatitude() {
        return lastLatitude;
    }

    public void setLastLatitude(BigDecimal lastLatitude) {
        this.lastLatitude = lastLatitude;
    }

    public BigDecimal getLastLongitude() {
        return lastLongitude;
    }

    public void setLastLongitude(BigDecimal lastLongitude) {
        this.lastLongitude = lastLongitude;
    }

    public Instant getLastLocationUpdated() {
        return lastLocationUpdated;
    }

    public void setLastLocationUpdated(Instant lastLocationUpdated) {
        this.lastLocationUpdated = lastLocationUpdated;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeliveryDriver that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
