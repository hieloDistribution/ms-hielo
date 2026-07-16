package com.sales.sync.auth.repository;

import com.sales.sync.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /**
     * Counts distinct active users that have a role with the given name.
     * Used by {@code com.sales.sync.auth.admin.AdminBootstrap} to detect
     * whether the first admin has already been seeded.
     *
     * <p>Query joins through the {@code user_roles} M:N table to the
     * {@code roles} table and filters by {@code roles.name}. Shape B
     * (V6 migration).
     */
    @Query("SELECT COUNT(DISTINCT u) FROM User u JOIN u.roles r "
            + "WHERE r.name = :roleName AND u.active = true")
    long countActiveByRoleName(@Param("roleName") String roleName);
}
