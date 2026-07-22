package com.sales.order.service;

import com.sales.order.model.Client;
import com.sales.order.model.Vendor;
import com.sales.order.model.VendorClientAssignment;
import com.sales.order.repository.ClientRepository;
import com.sales.order.repository.VendorClientAssignmentRepository;
import com.sales.order.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Domain service for the {@code (vendor, client)} assignment workflow.
 *
 * <p>The model is "permanent cartera" with a {@code (effective_from,
 * effective_to)} time-interval. Cartera permanente means we operate with
 * {@code effective_from = now()} and {@code effective_to = NULL} until the
 * assignment is closed. Re-assigning a client closes the previous active row
 * ({@code effective_to = now()}) and opens a new one. The DB-level partial
 * unique index {@code (vendor_id, client_id) WHERE effective_to IS NULL}
 * enforces "at most one active assignment per (vendor, client)" pair.
 *
 * <p>Cascade close: when an upstream user (preventista) is deactivated, the
 * sync-service calls {@link #cascadeCloseAssignmentsForVendor(UUID)} which
 * expires all active rows for that vendor, freeing the clients for the admin
 * to re-assign.
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    private final VendorRepository vendors;
    private final ClientRepository clients;
    private final VendorClientAssignmentRepository assignments;

    public AssignmentService(VendorRepository vendors,
                             ClientRepository clients,
                             VendorClientAssignmentRepository assignments) {
        this.vendors = vendors;
        this.clients = clients;
        this.assignments = assignments;
    }

    // --- commands --------------------------------------------------------

    /**
     * Assign {@code clientId} to {@code vendorId}. If the client already has
     * an active assignment to a different vendor, that row is closed first
     * ({@code effective_to = now()}) and the new row takes its place.
     *
     * @return the fresh client + assignment pair.
     * @throws ResponseStatusException 404 if client or vendor not found;
     *                                 409 if vendor is inactive or soft-deleted.
     */
    @Transactional
    public AssignmentResult assign(UUID clientId, UUID vendorId, UUID actorId) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Client not found: " + clientId));
        if (client.getDeletedAt() != null) {
            throw new ResponseStatusException(NOT_FOUND, "Client is soft-deleted");
        }
        Vendor vendor = vendors.findById(vendorId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vendor not found: " + vendorId));
        if (vendor.getDeletedAt() != null) {
            throw new ResponseStatusException(CONFLICT, "Vendor is soft-deleted");
        }
        if (!Boolean.TRUE.equals(vendor.getActive())) {
            throw new ResponseStatusException(CONFLICT, "Vendor is inactive");
        }

        Instant now = Instant.now();

        // Close any prior active assignment for this client.
        List<VendorClientAssignment> prior = assignments.findActiveByClientId(clientId);
        UUID previousVendorId = null;
        for (VendorClientAssignment old : prior) {
            old.expireAt(now);
            old.setUpdatedBy(actorId);
            assignments.save(old);
            previousVendorId = old.getVendorId();
        }

        VendorClientAssignment fresh = new VendorClientAssignment();
        fresh.setVendorId(vendorId);
        fresh.setClientId(clientId);
        fresh.activateFrom(now);
        fresh.setCreatedBy(actorId);
        fresh.setUpdatedBy(actorId);
        VendorClientAssignment saved = assignments.save(fresh);

        log.info("ASSIGN client={} vendor={} actor={} previousVendor={}",
                clientId, vendorId, actorId, previousVendorId);
        return new AssignmentResult(client, saved, previousVendorId);
    }

    /**
     * Close the active assignment for {@code clientId} (if any). Idempotent —
     * a no-op when the client is already unassigned.
     *
     * @return the freshly-unassigned Client (always present, since we 404 if
     *         the clientId does not exist).
     */
    @Transactional
    public Client unassign(UUID clientId, UUID actorId) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Client not found: " + clientId));
        List<VendorClientAssignment> active = assignments.findActiveByClientId(clientId);
        if (active.isEmpty()) {
            log.info("UNASSIGN client={} actor={} (no-op: nothing to close)", clientId, actorId);
            return client;
        }
        Instant now = Instant.now();
        for (VendorClientAssignment a : active) {
            a.expireAt(now);
            a.setUpdatedBy(actorId);
            assignments.save(a);
        }
        log.info("UNASSIGN client={} actor={} closedRows={}", clientId, actorId, active.size());
        return client;
    }

    /**
     * Cascade-close every active assignment that points at {@code vendorId}.
     * Called from sync-service when a preventista user is deactivated, so the
     * orphaned clients surface in {@link #getUnassignedClients()} for the admin
     * to re-assign.
     *
     * @return number of assignments closed.
     */
    @Transactional
    public int cascadeCloseAssignmentsForVendor(UUID vendorId) {
        List<VendorClientAssignment> active = assignments.findActiveByVendorId(vendorId);
        if (active.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        for (VendorClientAssignment a : active) {
            a.expireAt(now);
            assignments.save(a);
        }
        log.info("CASCADE_CLOSE vendor={} closedRows={}", vendorId, active.size());
        return active.size();
    }

    /**
     * Cascade-close for a given upstream {@code userId}: looks up the Vendor
     * with that userId (non-deleted) and closes its active assignments.
     * Returns 0 when no vendor matches (e.g. the user was never a preventista).
     */
    @Transactional
    public int cascadeCloseAssignmentsForUser(UUID userId) {
        java.util.Optional<Vendor> v = vendors.findByUserIdAndDeletedAtIsNull(userId);
        if (v.isEmpty()) {
            log.info("CASCADE_CLOSE user={} (no vendor found)", userId);
            return 0;
        }
        return cascadeCloseAssignmentsForVendor(v.get().getId());
    }

    /**
     * Returns the active cartera for the vendor associated with the given
     * {@code userId}. Used by the preventista dashboard
     * ({@code GET /api/v1/me/clients}).
     *
     * <p>Returns an empty list when the user is not a preventista (no vendor
     * row matches) so the caller doesn't need a special case for "not a vendor".
     *
     * @return list of {@link ClientView} (client + active assignment) for
     *         every non-deleted client currently assigned to the vendor.
     */
    @Transactional(readOnly = true)
    public List<ClientView> getCarteraForUser(UUID userId) {
        java.util.Optional<Vendor> v = vendors.findByUserIdAndDeletedAtIsNull(userId);
        if (v.isEmpty()) {
            log.info("getCarteraForUser user={} (no vendor found, returning empty)", userId);
            return List.of();
        }
        UUID vendorId = v.get().getId();
        List<VendorClientAssignment> active = assignments.findActiveByVendorId(vendorId);
        List<ClientView> out = new ArrayList<>(active.size());
        for (VendorClientAssignment a : active) {
            Client c = clients.findById(a.getClientId()).orElse(null);
            if (c != null && c.getDeletedAt() == null) {
                out.add(new ClientView(c, a));
            }
        }
        log.info("getCarteraForUser user={} vendor={} returned {} clients",
                userId, vendorId, out.size());
        return out;
    }

    // --- queries ---------------------------------------------------------

    /**
     * Return the active cartera for {@code vendorId}: the vendor plus its
     * currently-assigned clients.
     *
     * @throws ResponseStatusException 404 if vendor not found.
     */
    @Transactional(readOnly = true)
    public VendorCarteraView getCartera(UUID vendorId) {
        Vendor vendor = vendors.findById(vendorId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vendor not found: " + vendorId));
        List<VendorClientAssignment> active = assignments.findActiveByVendorId(vendorId);
        List<ClientView> clientViews = new ArrayList<>(active.size());
        for (VendorClientAssignment a : active) {
            Client c = clients.findById(a.getClientId()).orElse(null);
            if (c != null && c.getDeletedAt() == null) {
                clientViews.add(new ClientView(c, a));
            }
        }
        return new VendorCarteraView(vendor, clientViews);
    }

    /**
     * Return all non-deleted clients that have no active assignment (i.e.,
     * {@code effective_to IS NULL} for any assignment row pointing at them).
     */
    @Transactional(readOnly = true)
    public List<Client> getUnassignedClients() {
        List<Client> active = clients.findByDeletedAtIsNull();
        List<Client> orphans = new ArrayList<>();
        for (Client c : active) {
            List<VendorClientAssignment> a = assignments.findActiveByClientId(c.getId());
            if (a.isEmpty()) {
                orphans.add(c);
            }
        }
        return orphans;
    }

    // --- DTO-ish views (kept inside the service to avoid leaking JPA into controllers) ---

    public record VendorCarteraView(Vendor vendor, List<ClientView> clients) {}

    public record ClientView(Client client, VendorClientAssignment assignment) {}

    /**
     * Result of a successful assign: the client (already loaded), the fresh
     * assignment row, and the vendorId of the prior active assignment (or
     * {@code null} if the client was previously unassigned).
     */
    public record AssignmentResult(Client client, VendorClientAssignment assignment, UUID previousVendorId) {}
}