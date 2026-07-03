package com.sales.sync.auth.repository;

import com.sales.sync.SyncServiceApplication;
import com.sales.sync.auth.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED-first slice test for UserRepository.findByEmail. {@link DataJpaTest}
 * boots a JPA slice; before the User @Entity exists the slice fails to
 * load ("No qualifying bean of type UserRepository" / "Not a managed
 * type: class com.sales.sync.auth.model.User"). After the entity +
 * repository exist this test goes GREEN.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = SyncServiceApplication.class)
class UserRepositoryContractTest {

    @Autowired
    UserRepository users;

    @Test
    void findByEmail_returns_user_when_present() {
        User u = new User();
        u.setEmail("alice@example.com");
        u.setPasswordHash("$2a$04$dummybcrypthashfortest");
        users.save(u);

        Optional<User> found = users.findByEmail("alice@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void findByEmail_returns_empty_when_absent() {
        assertThat(users.findByEmail("nobody@example.com")).isEmpty();
    }
}
