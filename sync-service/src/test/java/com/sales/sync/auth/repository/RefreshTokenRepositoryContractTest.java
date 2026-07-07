package com.sales.sync.auth.repository;

import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.sales.sync.auth.it.AbstractPostgresIT;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenRepositoryContractTest extends AbstractPostgresIT {

    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;

    private RefreshToken seedRow(UUID userId, UUID family, boolean revoked, String hash) {
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenFamily(family);
        rt.setTokenHash(hash);
        rt.setExpiresAt(Instant.now().plusSeconds(3600));
        rt.setRevoked(revoked);
        return tokens.save(rt);
    }

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("$2a$04$dummybcrypthashfortest");
        return users.save(u);
    }

    @BeforeEach
    void clean() {
        tokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void findByTokenHash_returns_row_when_present() {
        User u = newUser("alice@example.com");
        String hash = "d".repeat(64);
        RefreshToken saved = seedRow(u.getId(), UUID.randomUUID(), false, hash);

        Optional<RefreshToken> found = tokens.findByTokenHash(hash);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByTokenHash_returns_empty_when_absent() {
        assertThat(tokens.findByTokenHash("z".repeat(64))).isEmpty();
    }

    @Test
    void findActiveFamilyByUserId_returns_latest_non_revoked_family() {
        User u = newUser("bob@example.com");
        UUID family = UUID.randomUUID();
        seedRow(u.getId(), family, false, "1".repeat(64));

        Optional<UUID> found = tokens.findActiveFamilyByUserId(u.getId());
        assertThat(found).contains(family);
    }

    @Test
    void findActiveFamilyByUserId_returns_empty_when_all_revoked() {
        User u = newUser("carol@example.com");
        seedRow(u.getId(), UUID.randomUUID(), true, "2".repeat(64));

        assertThat(tokens.findActiveFamilyByUserId(u.getId())).isEmpty();
    }

    @Test
    @Transactional
    void burnFamily_marks_every_non_revoked_row_in_family_as_revoked() {
        User u = newUser("dave@example.com");
        UUID family = UUID.randomUUID();

        RefreshToken a = seedRow(u.getId(), family, false, "a".repeat(64));
        RefreshToken b = seedRow(u.getId(), family, false, "b".repeat(64));
        seedRow(u.getId(), UUID.randomUUID(), false, "c".repeat(64)); // different family

        int affected = tokens.burnFamily(family);

        assertThat(affected).isEqualTo(2);
        assertThat(tokens.findById(a.getId()).orElseThrow().isRevoked()).isTrue();
        assertThat(tokens.findById(b.getId()).orElseThrow().isRevoked()).isTrue();
    }

    @Test
    @Transactional
    void deleteExpiredTokens_deletes_only_expired_tokens() {
        User u = newUser("cleanup@example.com");
        UUID family = UUID.randomUUID();

        // 1. Expired, not revoked
        RefreshToken expiredNotRevoked = new RefreshToken();
        expiredNotRevoked.setUserId(u.getId());
        expiredNotRevoked.setTokenFamily(family);
        expiredNotRevoked.setTokenHash("1".repeat(64));
        expiredNotRevoked.setExpiresAt(Instant.now().minusSeconds(60));
        expiredNotRevoked.setRevoked(false);
        tokens.save(expiredNotRevoked);

        // 2. Expired, revoked
        RefreshToken expiredRevoked = new RefreshToken();
        expiredRevoked.setUserId(u.getId());
        expiredRevoked.setTokenFamily(family);
        expiredRevoked.setTokenHash("2".repeat(64));
        expiredRevoked.setExpiresAt(Instant.now().minusSeconds(120));
        expiredRevoked.setRevoked(true);
        tokens.save(expiredRevoked);

        // 3. Not expired, not revoked
        RefreshToken activeNotRevoked = new RefreshToken();
        activeNotRevoked.setUserId(u.getId());
        activeNotRevoked.setTokenFamily(family);
        activeNotRevoked.setTokenHash("3".repeat(64));
        activeNotRevoked.setExpiresAt(Instant.now().plusSeconds(3600));
        activeNotRevoked.setRevoked(false);
        tokens.save(activeNotRevoked);

        // 4. Not expired, revoked (must be kept for replay detection)
        RefreshToken activeRevoked = new RefreshToken();
        activeRevoked.setUserId(u.getId());
        activeRevoked.setTokenFamily(family);
        activeRevoked.setTokenHash("4".repeat(64));
        activeRevoked.setExpiresAt(Instant.now().plusSeconds(1800));
        activeRevoked.setRevoked(true);
        tokens.save(activeRevoked);

        int deleted = tokens.deleteExpiredTokens(Instant.now());

        assertThat(deleted).isEqualTo(2);
        assertThat(tokens.findById(expiredNotRevoked.getId())).isEmpty();
        assertThat(tokens.findById(expiredRevoked.getId())).isEmpty();
        assertThat(tokens.findById(activeNotRevoked.getId())).isPresent();
        assertThat(tokens.findById(activeRevoked.getId())).isPresent();
    }
}
