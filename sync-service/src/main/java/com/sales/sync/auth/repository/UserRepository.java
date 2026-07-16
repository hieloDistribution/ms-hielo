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
     * Counts distinct active users that have the given role in their
     * {@code roles} collection. Used by
     * {@code com.sales.sync.auth.admin.AdminBootstrap} to detect whether
     * the first admin has already been seeded.
     *
     * <p>Implementation note: JPA derived queries on
     * {@code @ElementCollection} fields do not work portably across
     * Hibernate versions, so this is a JPQL query.
     */
    @Query("SELECT COUNT(DISTINCT u) FROM User u JOIN u.roles r "
            + "WHERE r = :role AND u.active = true")
    long countActiveByRole(@Param("role") User.Role role);
}
