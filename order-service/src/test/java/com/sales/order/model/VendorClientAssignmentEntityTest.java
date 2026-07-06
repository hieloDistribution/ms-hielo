package com.sales.order.model;

import com.sales.order.sync.SyncAuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA entity tests for {@link VendorClientAssignment} (spec scenarios T-03).
 *
 * <p>The partial-unique-index concurrency case (T-06) is NOT covered here:
 * Hibernate {@code create-drop} does not materialize DB partial unique
 * indexes, so it is deferred to the migration-backed repository test (PR-2
 * outside this slice).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class VendorClientAssignmentEntityTest {

    @Autowired
    private TestEntityManager em;

    // SyncAuthClient mock to satisfy PR-2 VendorRepositoryImpl's injection;
    // these tests use TestEntityManager.persist() directly, never vendorRepository.save().
    @MockBean
    private SyncAuthClient syncAuthClient;

    // --- T-03.1 fully active -------------------------------------------

    @Test
    void create_persists_fullyActive() {
        UUID vendorId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();

        VendorClientAssignment assignment = new VendorClientAssignment();
        assignment.setVendorId(vendorId);
        assignment.setClientId(clientId);
        // effectiveFrom / effectiveTo both NULL ⇒ fully active

        VendorClientAssignment persisted = em.persistFlushFind(assignment);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getVendorId()).isEqualTo(vendorId);
        assertThat(persisted.getClientId()).isEqualTo(clientId);
        assertThat(persisted.getEffectiveFrom()).isNull();
        assertThat(persisted.getEffectiveTo()).isNull();
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    // --- T-03.2 expire -------------------------------------------------

    @Test
    void expireAt_setsEffectiveTo_andBecomesInactive() {
        VendorClientAssignment persisted = em.persistFlushFind(newAssignment());

        Instant at = Instant.now();
        persisted.expireAt(at);
        em.persistAndFlush(persisted);

        VendorClientAssignment reloaded = em.find(VendorClientAssignment.class, persisted.getId());
        assertThat(reloaded.getEffectiveTo()).isEqualTo(at);
        assertThat(reloaded.isActive()).isFalse();
    }

    // --- T-03.3 activate-from-------------------------------------------

    @Test
    void activateFrom_setsEffectiveFrom_only() {
        VendorClientAssignment persisted = em.persistFlushFind(newAssignment());

        Instant from = Instant.now();
        persisted.activateFrom(from);
        em.persistAndFlush(persisted);

        VendorClientAssignment reloaded = em.find(VendorClientAssignment.class, persisted.getId());
        assertThat(reloaded.getEffectiveFrom()).isEqualTo(from);
        assertThat(reloaded.getEffectiveTo()).isNull();
        assertThat(reloaded.isActive()).isTrue();
    }

    // --- helpers -------------------------------------------------------

    private VendorClientAssignment newAssignment() {
        VendorClientAssignment a = new VendorClientAssignment();
        a.setVendorId(UUID.randomUUID());
        a.setClientId(UUID.randomUUID());
        return a;
    }
}