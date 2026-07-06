package com.sales.order.repository;

import com.sales.order.model.Order;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

     @Query("SELECT COALESCE(SUM(i.quantity * p.weightKg), 0) " +
           "FROM Order o JOIN o.items i JOIN Product p ON i.productId = p.id " +
           "WHERE o.salespersonId = :salespersonId AND o.createdAt >= :startOfDay")
    double sumOrderWeightBySalespersonAndDate(
            @Param("salespersonId") String salespersonId, 
            @Param("startOfDay") LocalDateTime startOfDay
    );
}
