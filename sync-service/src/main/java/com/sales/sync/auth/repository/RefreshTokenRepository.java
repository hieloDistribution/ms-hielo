package com.sales.sync.auth.repository;

import com.sales.sync.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") Instant now);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Returns the {@code token_family} UUID of the most recently created
     * non-revoked refresh row for the given user, if any exists.
     */
    @Query(value = """
            SELECT token_family FROM refresh_tokens
             WHERE user_id = :userId AND revoked = false
             ORDER BY created_at DESC LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findActiveFamilyByUserId(@Param("userId") UUID userId);

    /**
     * Burns a token family by marking every still-live row as revoked.
     * Idempotent: rows already {@code revoked=true} are left alone.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE refresh_tokens
               SET revoked = true
             WHERE token_family = :family
               AND revoked = false
            """, nativeQuery = true)
    int burnFamily(@Param("family") UUID tokenFamily);
}
