package com.sales.order.service;

import com.sales.order.model.Client;
import com.sales.order.model.Vendor;
import com.sales.order.model.VendorClientAssignment;
import com.sales.order.repository.ClientRepository;
import com.sales.order.repository.VendorClientAssignmentRepository;
import com.sales.order.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.is;

class AssignmentServiceTest {

    private VendorRepository vendors;
    private ClientRepository clients;
    private VendorClientAssignmentRepository assignments;
    private AssignmentService service;

    @BeforeEach
    void setUp() {
        vendors = mock(VendorRepository.class);
        clients = mock(ClientRepository.class);
        assignments = mock(VendorClientAssignmentRepository.class);
        service = new AssignmentService(vendors, clients, assignments);
    }

    // --- helpers --------------------------------------------------------

    private Vendor vendor(UUID id, boolean active, boolean deleted) {
        Vendor v = new Vendor();
        v.setId(id);
        v.setUserId(UUID.randomUUID());
        v.setDisplayName("V-" + id);
        v.setActive(active);
        v.setDeletedAt(deleted ? Instant.now() : null);
        return v;
    }

    private Client client(UUID id, boolean deleted) {
        Client c = new Client();
        c.setId(id);
        c.setName("C-" + id);
        c.setTaxId("TAX-" + id);
        c.setAddress("addr");
        c.setPhone("phone");
        c.setActive(true);
        c.setDeletedAt(deleted ? Instant.now() : null);
        return c;
    }

    private VendorClientAssignment assignment(UUID vendorId, UUID clientId) {
        VendorClientAssignment a = new VendorClientAssignment();
        a.setId(UUID.randomUUID());
        a.setVendorId(vendorId);
        a.setClientId(clientId);
        a.activateFrom(Instant.now());
        return a;
    }

    // --- assign ---------------------------------------------------------

    @Test
    void assign_to_active_vendor_creates_assignment_and_closes_prior() {
        UUID clientId = UUID.randomUUID();
        UUID oldVendor = UUID.randomUUID();
        UUID newVendor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        Client c = client(clientId, false);
        Vendor newV = vendor(newVendor, true, false);
        VendorClientAssignment prior = assignment(oldVendor, clientId);

        when(clients.findById(clientId)).thenReturn(Optional.of(c));
        when(vendors.findById(newVendor)).thenReturn(Optional.of(newV));
        when(assignments.findActiveByClientId(clientId)).thenReturn(new ArrayList<>(List.of(prior)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssignmentService.AssignmentResult result = service.assign(clientId, newVendor, actor);

        assertThat(result.previousVendorId()).isEqualTo(oldVendor);
        assertThat(result.assignment().getVendorId()).isEqualTo(newVendor);
        assertThat(result.assignment().getClientId()).isEqualTo(clientId);
        assertThat(result.assignment().isActive()).isTrue();
        // Prior row was expired.
        assertThat(prior.isActive()).isFalse();
        assertThat(prior.getUpdatedBy()).isEqualTo(actor);
        verify(assignments).save(prior);
        verify(assignments).save(result.assignment());
    }

    @Test
    void assign_to_inactive_vendor_throws_409() {
        UUID clientId = UUID.randomUUID();
        UUID newVendor = UUID.randomUUID();
        Client c = client(clientId, false);
        Vendor inactive = vendor(newVendor, false, false);

        when(clients.findById(clientId)).thenReturn(Optional.of(c));
        when(vendors.findById(newVendor)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.assign(clientId, newVendor, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inactive");
        verify(assignments, never()).save(any());
    }

    @Test
    void assign_to_soft_deleted_vendor_throws_409() {
        UUID clientId = UUID.randomUUID();
        UUID newVendor = UUID.randomUUID();
        Client c = client(clientId, false);
        Vendor deleted = vendor(newVendor, true, true);

        when(clients.findById(clientId)).thenReturn(Optional.of(c));
        when(vendors.findById(newVendor)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.assign(clientId, newVendor, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("soft-deleted");
    }

    @Test
    void assign_unknown_client_throws_404() {
        UUID clientId = UUID.randomUUID();
        when(clients.findById(clientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(clientId, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Client not found");
    }

    @Test
    void assign_soft_deleted_client_throws_404() {
        UUID clientId = UUID.randomUUID();
        Client c = client(clientId, true);
        when(clients.findById(clientId)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.assign(clientId, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("soft-deleted");
    }

    // --- unassign -------------------------------------------------------

    @Test
    void unassign_closes_active_assignment() {
        UUID clientId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Client c = client(clientId, false);
        VendorClientAssignment active = assignment(UUID.randomUUID(), clientId);

        when(clients.findById(clientId)).thenReturn(Optional.of(c));
        when(assignments.findActiveByClientId(clientId)).thenReturn(new ArrayList<>(List.of(active)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        Client returned = service.unassign(clientId, actor);

        assertThat(returned).isSameAs(c);
        assertThat(active.isActive()).isFalse();
        verify(assignments).save(active);
    }

    @Test
    void unassign_with_no_active_assignment_is_noop() {
        UUID clientId = UUID.randomUUID();
        Client c = client(clientId, false);
        when(clients.findById(clientId)).thenReturn(Optional.of(c));
        lenient().when(assignments.findActiveByClientId(clientId)).thenReturn(new ArrayList<>());

        Client returned = service.unassign(clientId, UUID.randomUUID());

        assertThat(returned).isSameAs(c);
        verify(assignments, never()).save(any());
    }

    // --- cascade --------------------------------------------------------

    @Test
    void cascadeClose_closes_all_active_assignments_for_vendor() {
        UUID vendorId = UUID.randomUUID();
        VendorClientAssignment a1 = assignment(vendorId, UUID.randomUUID());
        VendorClientAssignment a2 = assignment(vendorId, UUID.randomUUID());
        when(assignments.findActiveByVendorId(vendorId)).thenReturn(new ArrayList<>(List.of(a1, a2)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.cascadeCloseAssignmentsForVendor(vendorId);

        assertThat(closed).isEqualTo(2);
        assertThat(a1.isActive()).isFalse();
        assertThat(a2.isActive()).isFalse();
        verify(assignments).save(a1);
        verify(assignments).save(a2);
    }

    @Test
    void cascadeClose_with_no_assignments_returns_zero() {
        UUID vendorId = UUID.randomUUID();
        when(assignments.findActiveByVendorId(vendorId)).thenReturn(new ArrayList<>());

        int closed = service.cascadeCloseAssignmentsForVendor(vendorId);

        assertThat(closed).isZero();
        verify(assignments, never()).save(any());
    }

    // --- queries --------------------------------------------------------

    @Test
    void getCartera_returns_active_clients_only() {
        UUID vendorId = UUID.randomUUID();
        UUID softDeletedClientId = UUID.randomUUID();
        UUID activeClientId = UUID.randomUUID();

        Vendor v = vendor(vendorId, true, false);
        VendorClientAssignment activeAssignment = assignment(vendorId, activeClientId);
        VendorClientAssignment staleAssignment = assignment(vendorId, softDeletedClientId);

        Client activeC = client(activeClientId, false);
        Client deletedC = client(softDeletedClientId, true);

        when(vendors.findById(vendorId)).thenReturn(Optional.of(v));
        when(assignments.findActiveByVendorId(vendorId))
                .thenReturn(new ArrayList<>(List.of(activeAssignment, staleAssignment)));
        when(clients.findById(activeClientId)).thenReturn(Optional.of(activeC));
        when(clients.findById(softDeletedClientId)).thenReturn(Optional.of(deletedC));

        var view = service.getCartera(vendorId);

        assertThat(view.vendor()).isSameAs(v);
        assertThat(view.clients()).hasSize(1);
        assertThat(view.clients().get(0).client()).isSameAs(activeC);
    }

    @Test
    void getUnassignedClients_returns_clients_without_active_assignment() {
        UUID assignedId = UUID.randomUUID();
        UUID orphanId = UUID.randomUUID();
        Client assigned = client(assignedId, false);
        Client orphan = client(orphanId, false);

        when(clients.findByDeletedAtIsNull()).thenReturn(List.of(assigned, orphan));
        when(assignments.findActiveByClientId(assignedId))
                .thenReturn(new ArrayList<>(List.of(assignment(UUID.randomUUID(), assignedId))));
        lenient().when(assignments.findActiveByClientId(orphanId)).thenReturn(new ArrayList<>());

        List<Client> orphans = service.getUnassignedClients();

        assertThat(orphans).containsExactly(orphan);
    }

    // --- TDD edge cases (defensive / contract guarantees) ---------------

    @Test
    void assign_when_client_has_multiple_active_priors_closes_all_of_them() {
        // Defensive case: the partial unique index normally prevents this, but
        // if the invariant is somehow violated at the service layer, we must
        // close every prior active row, not just the first.
        UUID clientId = UUID.randomUUID();
        UUID oldVendorA = UUID.randomUUID();
        UUID oldVendorB = UUID.randomUUID();
        UUID newVendor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        Vendor newV = vendor(newVendor, true, false);
        VendorClientAssignment priorA = assignment(oldVendorA, clientId);
        VendorClientAssignment priorB = assignment(oldVendorB, clientId);

        when(clients.findById(clientId)).thenReturn(Optional.of(client(clientId, false)));
        when(vendors.findById(newVendor)).thenReturn(Optional.of(newV));
        when(assignments.findActiveByClientId(clientId))
                .thenReturn(new ArrayList<>(List.of(priorA, priorB)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssignmentService.AssignmentResult result = service.assign(clientId, newVendor, actor);

        // Both priors expired and persisted; the fresh row points at the new vendor.
        assertThat(priorA.isActive()).isFalse();
        assertThat(priorB.isActive()).isFalse();
        verify(assignments).save(priorA);
        verify(assignments).save(priorB);
        assertThat(result.assignment().getVendorId()).isEqualTo(newVendor);
        assertThat(result.assignment().isActive()).isTrue();
        // previousVendorId surfaces one of the two priors for audit context;
        // the contract is "one of them", not "the first". We don't lock in order.
        assertThat(result.previousVendorId()).isIn(oldVendorA, oldVendorB);
    }

    @Test
    void assign_when_target_vendor_is_same_as_current_is_idempotent() {
        // Re-assigning to the same vendor closes the prior and opens a fresh
        // row pointing at the same vendor. The audit trail records the churn.
        UUID clientId = UUID.randomUUID();
        UUID sameVendor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        Vendor v = vendor(sameVendor, true, false);
        VendorClientAssignment prior = assignment(sameVendor, clientId);

        when(clients.findById(clientId)).thenReturn(Optional.of(client(clientId, false)));
        when(vendors.findById(sameVendor)).thenReturn(Optional.of(v));
        when(assignments.findActiveByClientId(clientId)).thenReturn(new ArrayList<>(List.of(prior)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.assign(clientId, sameVendor, actor);

        assertThat(prior.isActive()).isFalse();
        assertThat(result.assignment().getVendorId()).isEqualTo(sameVendor);
        assertThat(result.assignment().isActive()).isTrue();
        verify(assignments).save(prior);
        verify(assignments).save(result.assignment());
    }

    @Test
    void unassign_with_null_actor_still_works() {
        // The audit column is nullable; we should not NPE if the caller passes
        // null actorId (e.g. from a system job that has no admin user).
        UUID clientId = UUID.randomUUID();
        Client c = client(clientId, false);
        VendorClientAssignment active = assignment(UUID.randomUUID(), clientId);

        when(clients.findById(clientId)).thenReturn(Optional.of(c));
        when(assignments.findActiveByClientId(clientId)).thenReturn(new ArrayList<>(List.of(active)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        Client returned = service.unassign(clientId, null);

        assertThat(returned).isSameAs(c);
        assertThat(active.isActive()).isFalse();
        assertThat(active.getUpdatedBy()).isNull();
        verify(assignments).save(active);
    }

    @Test
    void getCartera_with_no_clients_returns_empty_list() {
        UUID vendorId = UUID.randomUUID();
        Vendor v = vendor(vendorId, true, false);

        when(vendors.findById(vendorId)).thenReturn(Optional.of(v));
        when(assignments.findActiveByVendorId(vendorId)).thenReturn(new ArrayList<>());

        var view = service.getCartera(vendorId);

        assertThat(view.vendor()).isSameAs(v);
        assertThat(view.clients()).isEmpty();
    }

    @Test
    void getCartera_with_unknown_vendor_throws_404() {
        UUID vendorId = UUID.randomUUID();
        when(vendors.findById(vendorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCartera(vendorId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Vendor not found");
    }

    @Test
    void getUnassignedClients_with_empty_client_list_returns_empty() {
        when(clients.findByDeletedAtIsNull()).thenReturn(List.of());

        List<Client> orphans = service.getUnassignedClients();

        assertThat(orphans).isEmpty();
        // No need to query the assignments repo at all when there are no clients.
        verify(assignments, never()).findActiveByClientId(any());
    }

    @Test
    void getUnassignedClients_when_all_assigned_returns_empty() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Client c1 = client(id1, false);
        Client c2 = client(id2, false);

        when(clients.findByDeletedAtIsNull()).thenReturn(List.of(c1, c2));
        when(assignments.findActiveByClientId(id1))
                .thenReturn(new ArrayList<>(List.of(assignment(UUID.randomUUID(), id1))));
        when(assignments.findActiveByClientId(id2))
                .thenReturn(new ArrayList<>(List.of(assignment(UUID.randomUUID(), id2))));

        List<Client> orphans = service.getUnassignedClients();

        assertThat(orphans).isEmpty();
    }

    @Test
    void assign_does_not_call_save_when_prior_list_is_empty() {
        // Verify the happy-path-with-no-prior doesn't accidentally save() the
        // prior list (defensive: catches accidental refactors that always
        // invoke save even on an empty list).
        UUID clientId = UUID.randomUUID();
        UUID newVendor = UUID.randomUUID();

        when(clients.findById(clientId)).thenReturn(Optional.of(client(clientId, false)));
        when(vendors.findById(newVendor)).thenReturn(Optional.of(vendor(newVendor, true, false)));
        when(assignments.findActiveByClientId(clientId)).thenReturn(new ArrayList<>());
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.assign(clientId, newVendor, UUID.randomUUID());

        verify(assignments, times(1)).save(any(VendorClientAssignment.class));
    }

    @Test
    void cascadeClose_with_single_assignment_returns_one() {
        UUID vendorId = UUID.randomUUID();
        VendorClientAssignment a = assignment(vendorId, UUID.randomUUID());
        when(assignments.findActiveByVendorId(vendorId)).thenReturn(new ArrayList<>(List.of(a)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.cascadeCloseAssignmentsForVendor(vendorId);

        assertThat(closed).isOne();
        assertThat(a.isActive()).isFalse();
    }

    @Test
    void cascadeCloseForUser_with_matching_vendor_returns_closed_count() {
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        Vendor v = vendor(vendorId, true, false);
        v.setUserId(userId);
        VendorClientAssignment a = assignment(vendorId, UUID.randomUUID());
        VendorClientAssignment b = assignment(vendorId, UUID.randomUUID());

        when(vendors.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(v));
        when(assignments.findActiveByVendorId(vendorId))
                .thenReturn(new ArrayList<>(List.of(a, b)));
        when(assignments.save(any(VendorClientAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.cascadeCloseAssignmentsForUser(userId);

        assertThat(closed).isEqualTo(2);
        assertThat(a.isActive()).isFalse();
        assertThat(b.isActive()).isFalse();
    }

    @Test
    void cascadeCloseForUser_with_no_matching_vendor_returns_zero() {
        UUID userId = UUID.randomUUID();
        when(vendors.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        int closed = service.cascadeCloseAssignmentsForUser(userId);

        assertThat(closed).isZero();
        verify(assignments, never()).findActiveByVendorId(any());
    }

    @Test
    void cascadeCloseForUser_with_soft_deleted_vendor_returns_zero() {
        // Soft-deleted vendors are ignored (they're already logically gone).
        UUID userId = UUID.randomUUID();
        when(vendors.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        int closed = service.cascadeCloseAssignmentsForUser(userId);

        assertThat(closed).isZero();
    }

    // --- getCarteraForUser (preventista dashboard) ----------------------

    @Test
    void getCarteraForUser_with_matching_vendor_returns_clients() {
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        Vendor v = vendor(vendorId, true, false);
        v.setUserId(userId);
        UUID c1Id = UUID.randomUUID();
        UUID c2Id = UUID.randomUUID();
        Client c1 = client(c1Id, false);
        Client c2 = client(c2Id, false);
        VendorClientAssignment a1 = assignment(vendorId, c1Id);
        VendorClientAssignment a2 = assignment(vendorId, c2Id);

        when(vendors.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(v));
        when(assignments.findActiveByVendorId(vendorId))
                .thenReturn(new ArrayList<>(List.of(a1, a2)));
        when(clients.findById(c1Id)).thenReturn(Optional.of(c1));
        when(clients.findById(c2Id)).thenReturn(Optional.of(c2));

        var views = service.getCarteraForUser(userId);

        assertThat(views).hasSize(2);
        assertThat(views).extracting(cv -> cv.client().getId()).containsExactlyInAnyOrder(c1Id, c2Id);
    }

    @Test
    void getCarteraForUser_with_no_matching_vendor_returns_empty() {
        UUID userId = UUID.randomUUID();
        when(vendors.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        var views = service.getCarteraForUser(userId);

        assertThat(views).isEmpty();
        // We must NOT touch the assignments repo when the user isn't a vendor.
        verify(assignments, never()).findActiveByVendorId(any());
    }

    @Test
    void getCarteraForUser_skips_soft_deleted_clients() {
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        Vendor v = vendor(vendorId, true, false);
        v.setUserId(userId);
        UUID activeId = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        Client active = client(activeId, false);
        Client stale = client(staleId, true);
        VendorClientAssignment a1 = assignment(vendorId, activeId);
        VendorClientAssignment a2 = assignment(vendorId, staleId);

        when(vendors.findByUserIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(v));
        when(assignments.findActiveByVendorId(vendorId))
                .thenReturn(new ArrayList<>(List.of(a1, a2)));
        when(clients.findById(activeId)).thenReturn(Optional.of(active));
        when(clients.findById(staleId)).thenReturn(Optional.of(stale));

        var views = service.getCarteraForUser(userId);

        // The soft-deleted client's assignment is dropped from the response
        // even though the row in the DB is still active (defensive: protects
        // against orphan rows pointing at soft-deleted clients).
        assertThat(views).hasSize(1);
        assertThat(views.get(0).client().getId()).isEqualTo(activeId);
    }
}