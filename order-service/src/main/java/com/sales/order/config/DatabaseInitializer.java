package com.sales.order.config;

import com.sales.order.model.Client;
import com.sales.order.model.Product;
import com.sales.order.repository.ClientRepository;
import com.sales.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    /**
     * Deterministic id for the seeded demo Client. Allows tests and dev tools to
     * reference the seed row without touching the DB. See design §10.
     */
    static final UUID SEED_CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ProductRepository productRepository;
    private final ClientRepository clientRepository;

    public DatabaseInitializer(ProductRepository productRepository,
                                ClientRepository clientRepository) {
        this.productRepository = productRepository;
        this.clientRepository = clientRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (productRepository.count() == 0) {
            log.info("Inicializando catalogo de productos en la base de datos...");
            productRepository.saveAll(List.of(
                    new Product("PROD-ICE-001", "Saco de 10 kg (5 bolsas de 2 kg)", new BigDecimal("600.00"), 100, 10.0),
                    new Product("PROD-ICE-002", "Bolsa Hielo Cubos 5kg", new BigDecimal("250.00"), 300, 5.0),
                    new Product("PROD-ICE-003", "Bolsa Hielo Molido 10kg", new BigDecimal("450.00"), 150, 10.0),
                    new Product("PROD-ICE-004", "Bolsa Hielo Escamas 15kg", new BigDecimal("600.00"), 100, 15.0)
            ));
            log.info("Catálogo de bolsas de hielo insertado correctamente.");
        } else {
            log.info("El catalogo de productos ya cuenta con registros.");
        }

        // --- Client seed (PR-1) ------------------------------------------
        // Idempotent via deterministic UUID: `findById` skips if the row already
        // exists. The Vendor seed is deferred to PR-2 because it requires a
        // User to exist in sync_db (cross-DB integrity, design §10).
        if (clientRepository.findById(SEED_CLIENT_ID).isEmpty()) {
            log.info("Inicializando Client demo...");
            Client seed = new Client();
            seed.setId(SEED_CLIENT_ID);
            seed.setName("Cliente de Prueba");
            seed.setTaxId("00000000001");
            seed.setAddress("Av. Siempre Viva 742");
            seed.setPhone("+54 11 5555 0000");
            seed.setEmail("seed@example.com");
            clientRepository.save(seed);
            log.info("Client demo insertado correctamente (id={}).", SEED_CLIENT_ID);
        } else {
            log.info("Client demo ya existe (id={}).", SEED_CLIENT_ID);
        }
    }
}