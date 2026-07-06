package com.sales.order.repository;

import com.sales.order.model.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Vendor}.
 *
 * <p>PR-2 wires the {@link VendorRepositoryCustom#save} ({@link VendorRepositoryImpl}) into
 * this composite; {@code save} now consults {@code SyncAuthClient} before persisting.
 */
@Repository
public interface VendorRepository extends JpaRepository<Vendor, UUID>, VendorRepositoryCustom {

    Optional<Vendor> findByUserIdAndDeletedAtIsNull(UUID userId);

    boolean existsByUserIdAndDeletedAtIsNull(UUID userId);

    long countByUserIdAndDeletedAtIsNullAndActiveTrue(UUID userId);
}