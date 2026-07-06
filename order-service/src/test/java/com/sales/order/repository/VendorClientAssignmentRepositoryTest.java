package com.sales.order.repository;

import com.sales.order.model.VendorClientAssignment;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository query tests for {@link VendorClientAssignmentRepository}
 * (spec scenarios T-05 partial — active-listing and at-timestamp).
 *
 * <p>The partial-unique-index overlap test (T-06) requires the V1 migration
 * applied against real Postgres; it is NOT covered under Hibernate
 * {@code create-drop} here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class VendorClientAssignmentRepositoryTest {

    @Autowired
    private VendorClientAssignmentRepository assignmentRepository;

    @Autowired
    private TestEntityManager em;

    // Stub the cross-DB SyncAuthClient so VendorRepositoryImpl can wire.
    @MockBean
    private SyncAuthClient syncAuthClient;

    // --- active-listings -----------------------------------------------

    @Test
    void findActiveByClientId_returnsOnlyAssignmentsWithEffectiveToNull() {
        UUID vendorId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();

        VendorClientAssignment active = persist(vendorId, clientId);
        active.setEffectiveFrom(Instant.now().minus(1, ChronoUnit.HOURS));
        em.persistAndFlush(active);

        VendorClientAssignment expired = persist(UUID.randomUUID(), clientId);
        expired.expireAt(Instant.now());
        em.persistAndFlush(expired);

        List<VendorClientAssignment> activeForClient =
                assignmentRepository.findActiveByClientId(clientId);

        assertThat(activeForClient)
                .extracting(VendorClientAssignment::getId)
                .contains(active.getId())
                .doesNotContain(expired.getId());
    }

    // --- at-instant ----------------------------------------------------

    @Test
    void findAtInstant_returnsAssignmentActiveAtThatInstant() {
        UUID vendorId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant end = Instant.now().minus(1, ChronoUnit.HOURS);

        VendorClientAssignment assignment = persist(vendorId, clientId);
        assignment.setEffectiveFrom(start);
        assignment.expireAt(end);
        em.persistAndFlush(assignment);

        Instant mid = start.plus(30, ChronoUnit.MINUTES);

        Optional<VendorClientAssignment> found =
                assignmentRepository.findByVendorIdAndClientIdAndIntervalContains(vendorId, clientId, mid);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(assignment.getId());
    }

    // --- helpers -------------------------------------------------------

    private VendorClientAssignment persist(UUID vendorId, UUID clientId) {
        VendorClientAssignment a = new VendorClientAssignment();
        a.setVendorId(vendorId);
        a.setClientId(clientId);
        return em.persistFlushFind(a);
    }
}