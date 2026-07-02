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
                    new Product("PROD-001", "Coca Cola 1.5L", new BigDecimal("150.00"), 100),
                    new Product("PROD-002", "Papas Fritas Lays 150g", new BigDecimal("230.50"), 50),
                    new Product("PROD-003", "Chocolate Milka 100g", new BigDecimal("180.00"), 200)
            ));
            log.info("Productos insertados correctamente.");
        } else {
            log.info("El catalogo de productos ya cuenta con registros.");
        }
    }
}
