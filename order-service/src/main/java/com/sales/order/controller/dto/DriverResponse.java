package com.sales.order.controller.dto;

import com.sales.order.model.DeliveryDriver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DriverResponse(
        UUID id,
        UUID user_id,
        String display_name,
        String vehicle_type,
        String plate_number,
        BigDecimal stars,
        BigDecimal last_latitude,
        BigDecimal last_longitude,
        Instant last_location_updated,
        Boolean active
) {
    public static DriverResponse from(DeliveryDriver d) {
        return new DriverResponse(
                d.getId(),
                d.getUserId(),
                d.getDisplayName(),
                d.getVehicleType(),
                d.getPlateNumber(),
                d.getStars(),
                d.getLastLatitude(),
                d.getLastLongitude(),
                d.getLastLocationUpdated(),
                d.getActive()
        );
    }
}