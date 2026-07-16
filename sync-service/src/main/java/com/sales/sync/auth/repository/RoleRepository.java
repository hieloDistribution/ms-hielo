package com.sales.sync.auth.repository;

import com.sales.sync.auth.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code roles} table.
 *
 * <p>Owner: change {@code admin-console} PR2. Lookups by name are
 * used by {@link com.sales.sync.auth.admin.AdminBootstrap} (load
 * the {@code admin} role for the first-boot seed) and by
 * {@link com.sales.sync.auth.service.SignupService} (load the
 * {@code cliente} role for new signups).
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);
}
