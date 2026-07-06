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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA entity + Bean Validation tests for {@link Client} (spec scenarios T-01).
 *
 * <p>Uses {@code @DataJpaTest} with {@link Replace#NONE} so the real PostgreSQL
 * declared in the {@code test} profile is exercised (the project does not ship
 * H2). Hibernate {@code create-drop} rebuilds the schema from entity mappings
 * each run; the DB-only constructs from the V1 migration (partial unique index,
 * FK references) are not asserted here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class ClientEntityTest {

    @Autowired
    private TestEntityManager em;

    // The new VendorRepositoryImpl (PR-2) requires a SyncAuthClient bean.
    // The PR-1 @Component-annotated SyncAuthRestClientImpl is filtered out
    // by @DataJpaTest's TypeExcludeFilter, so we substitute a Mockito mock.
    // These tests use TestEntityManager.persist() directly and never invoke
    // vendorRepository.save(), so the mock is never exercised here.
    @MockBean
    private SyncAuthClient syncAuthClient;

    // @DataJpaTest doesn't auto-configure the Validator bean by default; build
    // it directly from the Jakarta Validation bootstrap (Hibernate Validator is
    // on the classpath via spring-boot-starter-validation).
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    // --- T-01.1 happy path ----------------------------------------------

    @Test
    void create_persistsAllRequiredFieldsAndSetsDefaultTimestamps() {
        Client client = validClient("Farmacia Central", "20300111111");

        Client persisted = em.persistFlushFind(client);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
        assertThat(persisted.getActive()).isTrue();
        assertThat(persisted.getDeletedAt()).isNull();
        assertThat(persisted.getName()).isEqualTo("Farmacia Central");
        assertThat(persisted.getTaxId()).isEqualTo("20300111111");
        assertThat(persisted.getAddress()).isEqualTo("Av. Siempre Viva 742");
    }

    // --- T-01.2 Bean Validation -----------------------------------------

    @Test
    void create_withBlankName_reportsConstraintViolation() {
        Client client = validClient("", "20300111112");
        Set<ConstraintViolation<Client>> violations = validator.validate(client);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void create_withLatitudeOutOfRange_reportsConstraintViolation() {
        Client client = validClient("Farmacia Norte", "20300111113");
        client.setLatitude(new BigDecimal("91"));
        Set<ConstraintViolation<Client>> violations = validator.validate(client);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("latitude"));
    }

    @Test
    void create_withLongitudeOutOfRange_reportsConstraintViolation() {
        Client client = validClient("Farmacia Sur", "20300111114");
        client.setLongitude(new BigDecimal("-181"));
        Set<ConstraintViolation<Client>> violations = validator.validate(client);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("longitude"));
    }

    @Test
    void create_withInvalidEmail_reportsConstraintViolation() {
        Client client = validClient("Farmacia Mail", "20300111119");
        client.setEmail("not-an-email");
        Set<ConstraintViolation<Client>> violations = validator.validate(client);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    // --- T-01.3 DB unique ----------------------------------------------

    @Test
    void create_withDuplicateTaxId_throwsDataIntegrityViolation() {
        em.persist(validClient("Farmacia A", "20300111115"));
        em.flush();

        Client second = validClient("Farmacia B (dup)", "20300111115");
        assertThatThrownBy(() -> {
            em.persist(second);
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    // --- T-01.6 soft-delete + idempotence -------------------------------

    @Test
    void softDelete_setsDeletedAtOnce_andIsIdempotent() {
        Client persisted = em.persistFlushFind(validClient("Farmacia X", "20300111116"));
        persisted.softDelete();
        em.persistAndFlush(persisted);

        Client reloaded = em.find(Client.class, persisted.getId());
        assertThat(reloaded.getDeletedAt()).isNotNull();
        Instant firstDeletedAt = reloaded.getDeletedAt();

        // Second soft-delete MUST NOT mutate deletedAt
        reloaded.softDelete();
        em.persistAndFlush(reloaded);

        Client reloadedAgain = em.find(Client.class, persisted.getId());
        assertThat(reloadedAgain.getDeletedAt()).isEqualTo(firstDeletedAt);
    }

    // --- T-01.7 restore -------------------------------------------------

    @Test
    void restore_clearsDeletedAt() {
        Client persisted = em.persistFlushFind(validClient("Farmacia Y", "20300111117"));
        persisted.softDelete();
        em.persistAndFlush(persisted);

        Client reloaded = em.find(Client.class, persisted.getId());
        assertThat(reloaded.getDeletedAt()).isNotNull();

        reloaded.restore();
        em.persistAndFlush(reloaded);

        Client restored = em.find(Client.class, persisted.getId());
        assertThat(restored.getDeletedAt()).isNull();
    }

    // --- T-01.8/9 created_by nullability --------------------------------

    @Test
    void created_by_defaultsNullWhenNoAuthContext() {
        Client persisted = em.persistFlushFind(validClient("Farmacia Z", "20300111118"));

        // created_by and updated_by MUST be null when no auth context attached
        assertThat(persisted.getCreatedBy()).isNull();
        assertThat(persisted.getUpdatedBy()).isNull();
    }

    // --- helpers --------------------------------------------------------

    private Client validClient(String name, String taxId) {
        Client c = new Client();
        c.setName(name);
        c.setTaxId(taxId);
        c.setAddress("Av. Siempre Viva 742");
        c.setPhone("+54 11 5555 0000");
        c.setEmail("test@example.com");
        return c;
    }
}