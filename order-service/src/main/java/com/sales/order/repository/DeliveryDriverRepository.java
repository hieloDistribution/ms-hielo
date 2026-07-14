package com.sales.order.repository;

import com.sales.order.model.DeliveryDriver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DeliveryDriver}.
 */
@Repository
public interface DeliveryDriverRepository extends JpaRepository<DeliveryDriver, UUID> {

    Optional<DeliveryDriver> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    List<DeliveryDriver> findByActiveTrueOrderByDisplayNameAsc();
}
