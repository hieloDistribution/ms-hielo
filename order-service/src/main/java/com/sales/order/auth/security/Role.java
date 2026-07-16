package com.sales.order.auth.security;

/**
 * Role names known to order-service. Mirrors the {@code roles.name}
 * column seeded by V6 of change {@code admin-console} (PR2 shape B).
 *
 * <p>Order-service does not own the {@code users} or {@code roles}
 * tables; it only consumes the JWT issued by sync-service. This enum
 * is the code-side mirror that the JWT parser uses to materialize
 * {@code Set<Role>} from the {@code roles} claim.
 */
public enum Role {
    admin, repartidor, cliente
}
