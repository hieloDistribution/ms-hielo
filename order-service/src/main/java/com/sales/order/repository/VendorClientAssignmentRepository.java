package com.sales.order.repository;

import com.sales.order.model.VendorClientAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link VendorClientAssignment}.
 *
 * <p>The partial unique index enforcing "at-most-one active assignment per
 * {@code (vendor_id, client_id)}" lives in the V1 Flyway migration and is
 * <em>not</em> replicated by Hibernate {@code create-drop} in tests.
 */
@Repository
public interface VendorClientAssignmentRepository
        extends JpaRepository<VendorClientAssignment, UUID> {

    /**
     * Active ({@code effective_to IS NULL}) assignments for a given client.
     */
    @Query("select a from VendorClientAssignment a " +
            "where a.clientId = :clientId and a.effectiveTo is null")
    List<VendorClientAssignment> findActiveByClientId(@Param("clientId") UUID clientId);

    /**
     * Active assignments for a given vendor.
     */
    @Query("select a from VendorClientAssignment a " +
            "where a.vendorId = :vendorId and a.effectiveTo is null")
    List<VendorClientAssignment> findActiveByVendorId(@Param("vendorId") UUID vendorId);

    /**
     * Point-in-time lookup: the assignment that is active at {@code at}.
     */
    @Query("select a from VendorClientAssignment a " +
            "where a.vendorId = :vendorId and a.clientId = :clientId " +
            "  and (a.effectiveFrom is null or a.effectiveFrom <= :at) " +
            "  and (a.effectiveTo is null or a.effectiveTo > :at)")
    Optional<VendorClientAssignment> findByVendorIdAndClientIdAndIntervalContains(
            @Param("vendorId") UUID vendorId,
            @Param("clientId") UUID clientId,
            @Param("at") Instant at);
}