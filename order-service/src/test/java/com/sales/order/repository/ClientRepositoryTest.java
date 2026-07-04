package com.sales.order.repository;

import com.sales.order.model.Client;
import com.sales.order.sync.SyncAuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository query tests for {@link ClientRepository} (spec scenarios T-02 /
 * "Default Query Filter"). The test class exercises:
 *
 * <ul>
 *   <li>active-listing excludes soft-deleted clients</li>
 *   <li>{@code existsByTaxIdAndDeletedAtIsNull} distinguishes active from soft-deleted</li>
 *   <li>{@code findByIdIncludingDeleted} returns soft-deleted rows when explicitly asked</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class ClientRepositoryTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private TestEntityManager em;

    // Stub the cross-DB SyncAuthClient so VendorRepositoryImpl can wire.
    @MockBean
    private SyncAuthClient syncAuthClient;

    // --- listing -------------------------------------------------------

    @Test
    void findByDeletedAtIsNull_excludesSoftDeletedClients() {
        Client active = persist("Farmacia Activa", "20300111200");
        Client softDeleted = persist("Farmacia Borrada", "20300111201");
        softDeleted.softDelete();
        em.persistAndFlush(softDeleted);

        List<Client> activeClients = clientRepository.findByDeletedAtIsNull();

        assertThat(activeClients)
                .extracting(Client::getId)
                .contains(active.getId())
                .doesNotContain(softDeleted.getId());
    }

    // --- tax_id uniqueness across soft-delete --------------------------

    @Test
    void existsByTaxIdAndDeletedAtIsNull_returnsTrueForActive_falseForSoftDeleted() {
        Client c = persist("Farmacia T", "20300111202");
        assertThat(clientRepository.existsByTaxIdAndDeletedAtIsNull("20300111202")).isTrue();

        c.softDelete();
        em.persistAndFlush(c);

        assertThat(clientRepository.existsByTaxIdAndDeletedAtIsNull("20300111202")).isFalse();
    }

    // --- includeDeleted -----------------------------------------------

    @Test
    void findByIdIncludingDeleted_returnsEntity_evenWhenSoftDeleted() {
        Client c = persist("Farmacia I", "20300111203");
        UUID id = c.getId();
        c.softDelete();
        em.persistAndFlush(c);

        Optional<Client> found = clientRepository.findByIdIncludingDeleted(id);

        assertThat(found).isPresent();
        assertThat(found.get().getDeletedAt()).isNotNull();
    }

    // --- helpers -------------------------------------------------------

    private Client persist(String name, String taxId) {
        Client c = new Client();
        c.setName(name);
        c.setTaxId(taxId);
        c.setAddress("Av. Siempre Viva 742");
        c.setPhone("+54 11 5555 0000");
        c.setEmail("test@example.com");
        return em.persistFlushFind(c);
    }
}