package com.sales.sync.repository;

import com.sales.sync.model.OfflineAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OfflineAudit}.
 */
@Repository
public interface OfflineAuditRepository extends JpaRepository<OfflineAudit, UUID> {

    List<OfflineAudit> findByDriverId(UUID driverId);
}
