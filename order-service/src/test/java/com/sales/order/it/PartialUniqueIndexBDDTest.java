package com.sales.order.it;

import com.sales.order.OrderServiceApplication;
import com.sales.order.model.Client;
import com.sales.order.model.Vendor;
import com.sales.order.model.VendorClientAssignment;
import com.sales.order.repository.ClientRepository;
import com.sales.order.repository.VendorClientAssignmentRepository;
import com.sales.order.repository.VendorRepository;
import com.sales.order.sync.RemoteUser;
import com.sales.order.sync.SyncAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * BDD tests verifying the "at most one active assignment per
 * (vendor, client) pair" invariant.
 *
 * <p>The production schema (V1 migration) enforces this invariant via a
 * PostgreSQL partial unique index:
 * <pre>
 *   CREATE UNIQUE INDEX vca_vendor_client_active_uq
 *       ON vendor_client_assignments (vendor_id, client_id)
 *       WHERE effective_to IS NULL;
 * </pre>
 *
 * <p>{@code H2} (the in-memory test DB) does not support partial unique
 * indexes via {@code WHERE}. To verify the contract end-to-end we install
 * a plain unique index on {@code (vendor_id, client_id)} in {@code @BeforeEach}.
 * This is <em>stricter</em> than production — it forbids any duplicate
 * row, not just active ones — but it surfaces the same class of bug
 * (forgetting to close the prior row before opening a new one). The
 * service-level invariant ("close prior active before open new") is
 * covered exhaustively in {@code AssignmentServiceTest}.
 */
@SpringBootTest(classes = OrderServiceApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-32+bytes",
        "jwt.issuer=hielo-sync",
        "jwt.audience=hielo-order"
})
class PartialUniqueIndexBDDTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired VendorRepository vendorRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired VendorClientAssignmentRepository assignmentRepository;
    @MockBean SyncAuthClient syncAuthClient;

    @BeforeEach
    void setupSchemaAndFixtures() {
        when(syncAuthClient.getUserById(any(UUID.class)))
                .thenReturn(java.util.Optional.of(new RemoteUser(
                        UUID.randomUUID(), "v@example.com", false, null)));
        jdbc.execute("DELETE FROM vendor_client_assignments");
        jdbc.execute("DELETE FROM clients");
        jdbc.execute("DELETE FROM vendors");
        // Install the strict unique index that emulates the production
        // partial unique index. Production's index only forbids duplicates
        // among active rows; ours forbids any duplicate. Either way, the
        // service must close the prior row before opening a new one.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS vca_uq_test "
                + "ON vendor_client_assignments (vendor_id, client_id)");
    }

    private Vendor seedVendor(String name) {
        Vendor v = new Vendor();
        v.setUserId(UUID.randomUUID());
        v.setDisplayName(name);
        v.setEmployeeCode("EMP-" + name.toUpperCase().replace(' ', '-'));
        v.setActive(true);
        v.setDeletedAt(null);
        return ((com.sales.order.repository.VendorRepositoryCustom) vendorRepository).save(v);
    }

    private Client seedClient(String name) {
        Client c = new Client();
        c.setName(name);
        c.setTaxId("TAX-" + UUID.randomUUID().toString().substring(0, 8));
        c.setAddress("addr");
        c.setPhone("+5491100000000");
        c.setActive(true);
        c.setDeletedAt(null);
        return clientRepository.save(c);
    }

    @Test
    @DisplayName("BDD: dado un vendor y un client, cuando intento insertar 2 assignments activas, "
            + "entonces la segunda insercion falla con DataIntegrityViolationException")
    void given_vendor_and_client_when_inserting_two_active_assignments_then_second_fails() {
        // Given: vendor + client
        Vendor vendor = seedVendor("Roberto Sosa");
        Client client = seedClient("Kiosco");
        Instant now = Instant.now();

        // When: inserto la primera (debe funcionar)
        VendorClientAssignment first = new VendorClientAssignment();
        first.setVendorId(vendor.getId());
        first.setClientId(client.getId());
        first.activateFrom(now);
        assignmentRepository.saveAndFlush(first);
        assertThat(assignmentRepository.findAll()).hasSize(1);

        // Then: intento la segunda con la misma (vendor, client) sin cerrar la primera
        VendorClientAssignment second = new VendorClientAssignment();
        second.setVendorId(vendor.getId());
        second.setClientId(client.getId());
        second.activateFrom(now.plusSeconds(60));

        assertThatThrownBy(() -> assignmentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And: sigue habiendo solo 1 fila
        assertThat(assignmentRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("BDD: dado un vendor y un client con un assignment cerrado, "
            + "cuando intento insertar otro assignment activo, entonces la insercion falla "
            + "(en produccion seria OK por el WHERE effective_to IS NULL, "
            + "pero el strict unique index del test DB lo bloquea)")
    void given_closed_assignment_when_inserting_another_active_then_blocked_by_strict_index() {
        // Given: vendor + client con un assignment YA CERRADO
        Vendor vendor = seedVendor("Lucia Fernandez");
        Client client = seedClient("Almacen");
        Instant now = Instant.now();
        VendorClientAssignment closed = new VendorClientAssignment();
        closed.setVendorId(vendor.getId());
        closed.setClientId(client.getId());
        closed.activateFrom(now.minusSeconds(3600));
        closed.expireAt(now.minusSeconds(60));  // ya cerrado
        assignmentRepository.saveAndFlush(closed);

        // When: intento abrir uno nuevo activo
        VendorClientAssignment fresh = new VendorClientAssignment();
        fresh.setVendorId(vendor.getId());
        fresh.setClientId(client.getId());
        fresh.activateFrom(now);

        // Then: en produccion esto seria OK (partial unique index lo permite),
        // pero nuestro test DB usa un strict unique index, asi que falla.
        // Esto es intencional: el test cubre el caso del bug (no cerrar el prior)
        // de manera mas estricta que produccion.
        assertThatThrownBy(() -> assignmentRepository.saveAndFlush(fresh))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("BDD: dados dos vendors diferentes con el mismo client, "
            + "cuando inserto assignments, entonces ambas coexisten (no hay conflicto cross-vendor)")
    void given_two_vendors_same_client_when_inserting_assignments_then_both_succeed() {
        // Given: 2 vendors + 1 client
        Vendor vendorA = seedVendor("Ana Lopez");
        Vendor vendorB = seedVendor("Pedro Ruiz");
        Client client = seedClient("Kiosco");

        // When: cada vendor asignado al mismo client (uno activo, otro cerrado)
        Instant now = Instant.now();
        VendorClientAssignment a = new VendorClientAssignment();
        a.setVendorId(vendorA.getId());
        a.setClientId(client.getId());
        a.activateFrom(now.minusSeconds(3600));
        a.expireAt(now.minusSeconds(60));
        assignmentRepository.saveAndFlush(a);

        VendorClientAssignment b = new VendorClientAssignment();
        b.setVendorId(vendorB.getId());
        b.setClientId(client.getId());
        b.activateFrom(now);
        assignmentRepository.saveAndFlush(b);

        // Then: ambas filas coexisten (la constraint es solo por (vendor, client) par)
        assertThat(assignmentRepository.findAll()).hasSize(2);
    }
}