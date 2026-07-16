package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.Role;
import com.sales.sync.auth.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Idempotent seeder for the {@code roles} table.
 *
 * <p>Production path: the V6 Flyway migration seeds the three known roles
 * ({@code admin}, {@code repartidor}, {@code cliente}) with descriptive
 * text. After V6, {@code RolesSeeder} runs as a no-op (the
 * {@code findByName} check returns present).
 *
 * <p>Test path: the test profile disables Flyway and uses Hibernate
 * {@code ddl-auto=create-drop}, so the {@code roles} table is created
 * empty. {@code RolesSeeder} runs at higher precedence than
 * {@link AdminBootstrap} and seeds the three roles from the
 * application code so that downstream consumers (AdminBootstrap,
 * SignupService) find them.
 *
 * <p>The order {@link Ordered#HIGHEST_PRECEDENCE} ensures this runs
 * before {@code AdminBootstrap}, which depends on the {@code admin}
 * role.
 *
 * <p>Owner: change {@code admin-console} PR2 (shape B).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RolesSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RolesSeeder.class);

    /** Stable UUIDs so that dev/prod migration assertions are reproducible. */
    private static final List<Seed> SEEDS = List.of(
            new Seed("00000000-0000-0000-0000-00000000a001", "admin",
                    "System administrator with full access to /api/v1/admin/**"),
            new Seed("00000000-0000-0000-0000-00000000a002", "repartidor",
                    "Distribution driver — accepts orders, reports GPS location"),
            new Seed("00000000-0000-0000-0000-00000000a003", "cliente",
                    "End customer — places and tracks orders")
    );

    private final RoleRepository roles;

    public RolesSeeder(RoleRepository roles) {
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Seed seed : SEEDS) {
            if (roles.findByName(seed.name).isPresent()) {
                continue;
            }
            Role r = new Role();
            r.setId(java.util.UUID.fromString(seed.id));
            r.setName(seed.name);
            r.setDescription(seed.description);
            roles.save(r);
            log.info("seeded role id={} name={}", seed.id, seed.name);
        }
    }

    private record Seed(String id, String name, String description) { }
}
