package com.sales.order.model;

import com.sales.order.sync.SyncAuthClient;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA entity + Bean Validation tests for {@link Vendor} (spec scenarios T-02).
 *
 * <p>Skips the cross-DB user_id integrity check — that is verified in PR-2
 * via {@code VendorRepositoryTest} against a mocked {@code SyncAuthClient}.
 * Here we only assert entity mapping, Bean Validation, and soft-delete
 * idempotence.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class VendorEntityTest {

    @Autowired
    private TestEntityManager em;

    // SyncAuthClient mock to satisfy the PR-2 VendorRepositoryImpl dependency
    // injection; this test uses TestEntityManager.persist() directly.
    @MockBean
    private SyncAuthClient syncAuthClient;

    // @DataJpaTest doesn't auto-configure the Validator bean; build it directly.
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    // --- T-02.1 happy path ----------------------------------------------

    @Test
    void create_persistsWithUuidAndTimestamps() {
        Vendor vendor = validVendor("Vendedor Demo", UUID.randomUUID());

        Vendor persisted = em.persistFlushFind(vendor);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
        assertThat(persisted.getActive()).isTrue();
        assertThat(persisted.getDeletedAt()).isNull();
        assertThat(persisted.getDisplayName()).isEqualTo("Vendedor Demo");
    }

    // --- T-02.2 Bean Validation -----------------------------------------

    @Test
    void create_withBlankDisplayName_reportsConstraintViolation() {
        Vendor vendor = validVendor("", UUID.randomUUID());
        Set<ConstraintViolation<Vendor>> violations = validator.validate(vendor);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    void create_withNullUserId_reportsConstraintViolation() {
        Vendor vendor = validVendor("Vendedor X", null);
        Set<ConstraintViolation<Vendor>> violations = validator.validate(vendor);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("userId"));
    }

    // --- T-02.3 soft-delete idempotence --------------------------------

    @Test
    void softDelete_isIdempotent() {
        Vendor persisted = em.persistFlushFind(validVendor("Vendedor Z", UUID.randomUUID()));
        persisted.softDelete();
        em.persistAndFlush(persisted);

        Vendor reloaded = em.find(Vendor.class, persisted.getId());
        assertThat(reloaded.getDeletedAt()).isNotNull();
        Instant firstDeletedAt = reloaded.getDeletedAt();

        reloaded.softDelete();
        em.persistAndFlush(reloaded);

        Vendor reloadedAgain = em.find(Vendor.class, persisted.getId());
        assertThat(reloadedAgain.getDeletedAt()).isEqualTo(firstDeletedAt);
    }

    // --- helpers --------------------------------------------------------

    private Vendor validVendor(String displayName, UUID userId) {
        Vendor v = new Vendor();
        v.setUserId(userId);
        v.setDisplayName(displayName);
        v.setEmployeeCode("EMP-001");
        v.setPhone("+54 11 5555 1234");
        v.setEmail("vendor@example.com");
        return v;
    }
}