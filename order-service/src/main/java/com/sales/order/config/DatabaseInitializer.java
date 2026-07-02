package com.sales.order.config;

import com.sales.order.model.Product;
import com.sales.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final ProductRepository productRepository;

    public DatabaseInitializer(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (productRepository.count() == 0) {
            log.info("Inicializando catalogo de productos en la base de datos...");
            productRepository.saveAll(List.of(
                    new Product("PROD-ICE-001", "Bolsa Hielo Cubos 2kg", new BigDecimal("120.00"), 500),
                    new Product("PROD-ICE-002", "Bolsa Hielo Cubos 5kg", new BigDecimal("250.00"), 300),
                    new Product("PROD-ICE-003", "Bolsa Hielo Molido 10kg", new BigDecimal("450.00"), 150),
                    new Product("PROD-ICE-004", "Bolsa Hielo Escamas 15kg", new BigDecimal("600.00"), 100)
            ));
            log.info("Catálogo de bolsas de hielo insertado correctamente.");
        } else {
            log.info("El catalogo de productos ya cuenta con registros.");
        }
    }
}
