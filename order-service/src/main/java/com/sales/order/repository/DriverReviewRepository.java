package com.sales.order.repository;

import com.sales.order.model.DriverReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DriverReviewRepository extends JpaRepository<DriverReview, UUID> {
    List<DriverReview> findByDriverIdOrderByCreatedAtDesc(UUID driverId);
    boolean existsByOrderId(String orderId);
}