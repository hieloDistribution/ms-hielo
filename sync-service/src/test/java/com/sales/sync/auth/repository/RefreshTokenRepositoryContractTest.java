package com.sales.sync.auth.repository;

import com.sales.sync.SyncServiceApplication;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = SyncServiceApplication.class)
class RefreshTokenRepositoryContractTest {

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
}
