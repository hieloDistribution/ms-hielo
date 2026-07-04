package com.sales.order.repository;

import com.sales.order.model.Vendor;

/**
 * Custom-side of the {@link VendorRepository} fractal — Spring Data routes
 * methods defined on this interface to {@link VendorRepositoryImpl}.
 *
 * <p>PR-2 introduces a single overridden {@link #save} that consults
 * {@link com.sales.order.sync.SyncAuthClient} before delegating to
 * {@link jakarta.persistence.EntityManager#persist}/{@code merge}. Stock
 * CRUD inherited from {@code JpaRepository} remains routed to
 * {@code SimpleJpaRepository}.
 */
public interface VendorRepositoryCustom {

    /**
     * Persist a Vendor after confirming {@code vendor.userId} points at a
     * live upstream User via {@link com.sales.order.sync.SyncAuthClient}.
     *
     * @throws com.sales.order.sync.exceptions.UnknownUserException if the
     *         upstream reports the user is unknown, locked, or deleted
     */
    <S extends Vendor> S save(S entity);
}