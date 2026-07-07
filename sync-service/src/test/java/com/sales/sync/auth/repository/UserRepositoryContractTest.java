package com.sales.sync.auth.repository;

import com.sales.sync.auth.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.sales.sync.auth.it.AbstractPostgresIT;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;

/**
 * RED-first slice test for UserRepository.findByEmail.
 */
class UserRepositoryContractTest extends AbstractPostgresIT {

    @Autowired
    UserRepository users;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

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
