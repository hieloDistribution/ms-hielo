package com.sales.order.repository;

import com.sales.order.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Client}.
 *
 * <p>Default views filter to non-soft-deleted rows
 * ({@code active = true AND deleted_at IS NULL}); opt-out queries
 * ({@code findByIdIncludingDeleted}) are explicit.
 */
@Repository
public interface ClientRepository extends JpaRepository<Client, UUID> {

    /**
     * Active clients only.
     */
    List<Client> findByDeletedAtIsNull();

    /**
     * Active client lookup by id. Returns empty if soft-deleted.
     */
    Optional<Client> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Opt-out: return the row regardless of soft-delete state.
     */
    @Query("select c from Client c where c.id = :id")
    Optional<Client> findByIdIncludingDeleted(@Param("id") UUID id);

    /**
     * Distinct-from-soft-deleted uniqueness probe (used by create validation
     * before flushing, before any DB-level {@code UNIQUE} violation).
     */
    boolean existsByTaxIdAndDeletedAtIsNull(String taxId);
}