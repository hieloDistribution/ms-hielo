package com.sales.order.repository;

import com.sales.order.model.Vendor;
import com.sales.order.sync.SyncAuthClient;
import com.sales.order.sync.exceptions.UnknownUserException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementation of {@link VendorRepositoryCustom} — overrides JPA's
 * {@link #save} so the cross-DB Vendor↔User integrity invariant fires BEFORE
 * the row is written (design §3.1 forward direction + §5.2).
 *
 * <p>Spring Data auto-wires this class (Spring Boot 2.x+ pattern for custom
 * repository impls where the postfix {@code Impl} matches
 * {@code spring.data.jpa.repository.implementation-postfix=Impl}, the Boot
 * default).
 */
@Component("vendorRepositoryImpl")
public class VendorRepositoryImpl implements VendorRepositoryCustom {

    private final EntityManager em;
    private final SyncAuthClient syncAuthClient;

    public VendorRepositoryImpl(EntityManager em, SyncAuthClient syncAuthClient) {
        this.em = em;
        this.syncAuthClient = syncAuthClient;
    }

    @Override
    @Transactional
    public <S extends Vendor> S save(S entity) {
        // Forward-direction integrity check (D-01, design §3.1 step 2).
        // An empty Optional means sync-service reported the user as unknown,
        // locked, or deleted; the integrity invariant insists we reject the
        // write.
        Optional<?> result = syncAuthClient.getUserById(entity.getUserId());
        if (result.isEmpty()) {
            throw new UnknownUserException(
                    "vendor.user_id " + entity.getUserId() + " does not reference a live User in sync_db");
        }
        // Mirror JPA SimpleJpaRepository.save's persist/merge branch.
        if (entity.getId() == null) {
            em.persist(entity);
            return entity;
        }
        Vendor merged = em.merge(entity);
        @SuppressWarnings("unchecked")
        S cast = (S) merged;
        return cast;
    }
}