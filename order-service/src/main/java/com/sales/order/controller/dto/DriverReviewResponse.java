package com.sales.order.controller.dto;

import com.sales.order.model.DriverReview;

import java.time.Instant;
import java.util.UUID;

public record DriverReviewResponse(
        UUID id,
        UUID driver_id,
        String order_id,
        short rating,
        String comment,
        UUID created_by,
        Instant created_at
) {
    public static DriverReviewResponse from(DriverReview r) {
        return new DriverReviewResponse(
                r.getId(),
                r.getDriverId(),
                r.getOrderId(),
                r.getRating() == null ? 0 : r.getRating(),
                r.getComment(),
                r.getCreatedBy(),
                r.getCreatedAt()
        );
    }
}