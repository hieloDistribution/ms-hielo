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

    Optional<RefreshToken> findFirstByUserIdAndRevokedFalseOrderByCreatedAtDesc(UUID userId);

    /**
     * Returns the {@code token_family} UUID of the most recently created
     * non-revoked refresh row for the given user, if any exists.
     */
    default Optional<UUID> findActiveFamilyByUserId(@Param("userId") UUID userId) {
        return findFirstByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId)
                .map(RefreshToken::getTokenFamily);
    }

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
