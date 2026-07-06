# Proposal — core-domain-model

**Change:** `core-domain-model`
**Project:** `hielo`
**Phase:** proposal
**Date:** 2026-07-03
**Status:** complete (product question round already answered)

---

## Intent

The `core-domain-model` change introduces three foundational entities — `Client`, `Vendor`, and `VendorClientAssignment` — into the `order-service` persistence layer. These entities establish the canonical data model for the multi-vendor sales workflow: clients who place orders, vendors who fulfill them, and the business-rule table that governs which vendor may serve which client at any point in time.

This change is a prerequisite for the follow-on `order-fk-migration` SDD, which will wire `Order.clientId` and `Order.salespersonId` from their current loose-`String` form to proper FK references pointing at these new tables. It is also the first step toward replacing ad-hoc client/vendor management (currently scattered across `Order.notes`, user comments, and undocumented spreadsheet exports) with a consistent, auditable domain model.

**Target users:** sales operations teams (managing client master data), field sales representatives (assigned to client portfolios), and integrations that push order data with a `clientId` today but have no authoritative client registry to validate against.

---

## Locked product decisions

The following decisions were confirmed in the proposal question round with the user and are binding for this SDD and downstream changes.

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **Vendor ↔ User cardinality is 1:1.** `Vendor.userId` is UNIQUE NOT NULL. The legacy `User.vendorId` (currently nullable in `users` inside `sync_db`) is vestigial in the new model and will be deprecated in a follow-up SDD. | One auth identity maps to one vendor identity; avoids the complexity of a single User acting as multiple Vendors. |
| 2 | **`VendorClientAssignment` uses simple N:N semantics.** Surrogate UUID PK; `vendorId` FK; `clientId` FK; `effective_from` nullable (null = "from creation"); `effective_to` nullable (null = "still active"). No `is_primary`, no `zone`, no product-category restrictions in this change. | Keeps the initial model lean; temporal scope is sufficient for v1. |
| 3 | **Soft-delete on `Client` and `Vendor`.** Both carry `deleted_at TIMESTAMP NULL`. Reporting queries filter `deleted_at IS NULL` by default. `erased_at` (GDPR right-to-erasure) is deferred to a future change. | Aligns with standard audit-trail practice; avoids accidental data loss before FK migration completes. |
| 4 | **`core-domain-model` stops at the three new entities.** It does NOT migrate `Order.clientId` or `Order.salespersonId` from `String` to FK. That work belongs to `order-fk-migration`, a separate SDD. | Keeps each SDD small and reviewable; the loose-String state is documented as an accepted v1 gap. |
| 5 | **`Client` attributes:** `id` (UUID PK), `name`, `tax_id` (UNIQUE NOT NULL), `address`, `phone`, `email` (NULL), `active` (DEFAULT true), `deleted_at` (NULL), `latitude`/`longitude` (NULL DECIMAL(9,6)), `created_at`/`updated_at`/`created_by`/`updated_by`. Excludes `price_list_id`, `payment_conditions`, `payment_term_days`, `payment_method`, `credit_limit` (deferred to a pricing/payments SDD). | Matches the minimal viable client record needed for order routing and sales-rep assignment. |
| 6 | **`Vendor` attributes:** `id` (UUID PK), `user_id` (UUID UNIQUE NOT NULL), `display_name`, `employee_code` (UNIQUE NULL), `phone`, `email`, `active` (DEFAULT true), `deleted_at`, `latitude`/`longitude`, `created_at`/`updated_at`/`created_by`/`updated_by`. Delivered divergences from `Client`: `user_id` (1:1 anchor to auth, declared as UNIQUE INDEX only — see §"Cross-database architecture" below); `employee_code` (legajo interno); no `tax_id` because vendors authenticate via `User`, not tax identity. | Mirrors `Client` conventions where applicable; diverges only on the auth-anchor field and removes tax identity (handled by auth module). |

---

## Scope

### In scope
- JPA entity classes: `Client`, `Vendor`, `VendorClientAssignment`
- Spring Data JPA repository interfaces: `ClientRepository`, `VendorRepository`, `VendorClientAssignmentRepository`
- Flyway migration script: `V1__create_clients_vendors_assignments.sql` for `order-service` (this change introduces Flyway to `order-service` — see §"Migration & rollback")
- Extension of `DatabaseInitializer` (located at `com.sales.order.config.DatabaseInitializer`) to seed at least one `Client` and one `Vendor` (linked to an existing `User`) for local dev startup. Seed is idempotent.
- Unit tests for JPA entity behavior (soft-delete lifecycle, UUID generation, audit timestamps)
- Integration tests: create / read / soft-delete for each entity
- `openspec/specs/` delta capability flags (authored in the `sdd-spec` phase next)
- Configure `spring.flyway.enabled=true` (with `baseline-on-migrate=true` and `baseline-version=0`) in `order-service/src/main/resources/application.yml`

### Out of scope (deferred)

| Item | Deferred to |
|------|-------------|
| `Order.clientId` / `Order.salespersonId` String → FK migration | `order-fk-migration` SDD |
| `User.vendorId` column deprecation in `users` (in `sync_db`) | Follow-up SDD after `order-fk-migration` |
| GDPR `erased_at` column on `Client` and `Vendor` | Future GDPR-change SDD |
| `Zone` concept on `VendorClientAssignment` | Future SDD |
| Vendor exclusivity / primary-flag on assignments | Future SDD |
| Product-category scoping of assignments | Future SDD |
| `price_list_id`, `payment_conditions`, `payment_term_days`, `payment_method`, `credit_limit` on `Client` | Pricing/payments SDD |
| Modifying existing `Order`, `OrderItem`, `Product`, or any controller | Out of scope entirely |

---

## Affected areas

### `order-service`
**New files (created by `sdd-apply`):**
- `src/main/java/com/sales/order/model/Client.java`
- `src/main/java/com/sales/order/model/Vendor.java`
- `src/main/java/com/sales/order/model/VendorClientAssignment.java`
- `src/main/java/com/sales/order/repository/ClientRepository.java`
- `src/main/java/com/sales/order/repository/VendorRepository.java`
- `src/main/java/com/sales/order/repository/VendorClientAssignmentRepository.java`
- `src/main/resources/db/migration/V1__create_clients_vendors_assignments.sql`
- `src/test/java/com/sales/order/model/ClientEntityTest.java`
- `src/test/java/com/sales/order/model/VendorEntityTest.java`
- `src/test/java/com/sales/order/model/VendorClientAssignmentEntityTest.java`
- Integration tests under `src/test/java/com/sales/order/repository/`

> Package paths follow the existing convention `com.sales.order.{model,repository,service,controller}` (Package by Layers) — the proposal's earlier reference to a hypothetical `com.sales.order.domain.*` package was a draft authoring artifact and is **not adopted**.

**Modified files:**
- `src/main/java/com/sales/order/config/DatabaseInitializer.java` — add seed guard for `Client` and `Vendor`
- `src/main/resources/application.yml` — enable Flyway on `order-service` with `baseline-on-migrate=true` to coexist with the existing unmanaged `orders`, `order_items`, and `products` tables (already created by JPA's earlier `ddl-auto=update` runs; see §"Migration & rollback")

**Unchanged (existing tests must not break):**
- `Order`, `OrderItem`, `Product` (in `com.sales.order.model`) — no modifications
- `OrderService`, `OrderController` — no modifications
- `OrderServiceBootIT`, `ApplicationContextSmokeIT` — no modifications

### `sync-service`
None. `VendorContext` and `User` in `sync-service` are not modified; the 1:1 `Vendor.user_id` field targets `users.id` but the auth schema in `sync_db` is unchanged in this SDD.

### `auth` module
None. `Vendor.user_id` will declare only a UNIQUE index (no FK) — see §"Cross-database architecture" below.

### `openspec/specs/`
The `sdd-spec` phase should author three new capability specs:
1. **`client-management`** — CRUD lifecycle, soft-delete, audit fields, tax-id uniqueness
2. **`vendor-management`** — CRUD lifecycle, 1:1 link to `User`, soft-delete, audit fields, cross-DB integrity assertion
3. **`vendor-client-assignment`** — N:N assignment, temporal scope (`effective_from` / `effective_to`), partial uniqueness for active intervals

---

## Cross-database architecture (binding constraint)

PostgreSQL does not allow foreign keys across database boundaries. Because:

- `users` lives in `sync_db` (managed by `sync-service`, owned by the auth-foundation SDD — see `sync-service/src/main/resources/db/migration/V1__create_users_and_refresh_tokens.sql`).
- `vendors` will live in `order_db` (managed by `order-service`).

…a physical FK `vendors.user_id → users(id)` is impossible.

**Decision:** `vendors.user_id` is declared as a UUID column with a **UNIQUE index only**, not a foreign key. The 1:1 invariant between `User` and `Vendor` is enforced at the **service layer** in `order-service`, via one of:

- (preferred for v1) A repository-layer pre-write assertion: `VendorRepository.save()` makes a synchronous HTTP call to a not-yet-existing `sync-service` endpoint (e.g., `GET /internal/auth/users/{id}`) to verify the user exists and is not soft-locked. Failures abort the write.
- (alternative) An outbox-driven eventual-consistency check, similar to the `processed_requests` pattern. Heavier; not justified for v1.

The chosen mechanism MUST be decided in `sdd-design` and proposed as part of `vendor-management` in `sdd-spec`. This proposal does not pick between the two — it surfaces the constraint and lets design make the call.

> If the team later decides to fold `order_db` and `sync_db` into a single database (which would simplify this entire class of constraints), the change is best driven by a separate "unified-database" SDD and is out of scope here.

---

## Entity schemas

Mirror the naming convention established in `sync-service`'s `V1__create_users_and_refresh_tokens.sql`: `snake_case` columns, `timestamptz` for timestamps, `pgcrypto` for `gen_random_uuid()`.

### Table: `clients`

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE clients (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            varchar(255) NOT NULL,           -- razón social / nombre comercial
    tax_id          varchar(50)  NOT NULL,           -- ruc/nit
    address         varchar(500) NOT NULL,
    phone           varchar(50)  NOT NULL,
    email           varchar(320),
    active          boolean     NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    latitude        numeric(9,6),
    longitude       numeric(9,6),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,

    CONSTRAINT clients_tax_id_uq UNIQUE (tax_id)
);

CREATE INDEX idx_clients_deleted_at_null ON clients (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_clients_tax_id ON clients (tax_id);
```

### Table: `vendors`

```sql
CREATE TABLE vendors (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL,            -- see "Cross-database architecture"
    display_name    varchar(255) NOT NULL,
    employee_code   varchar(50),
    phone           varchar(50),
    email           varchar(320),
    active          boolean     NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    latitude        numeric(9,6),
    longitude       numeric(9,6),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid

    -- No foreign key on user_id: see "Cross-database architecture" section above.
    -- The 1:1 invariant is enforced at the service layer (decision: TBD in sdd-design).
);

CREATE UNIQUE INDEX vendors_user_id_uq ON vendors (user_id);
CREATE INDEX idx_vendors_deleted_at_null ON vendors (deleted_at) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_vendors_employee_code ON vendors (employee_code) WHERE employee_code IS NOT NULL;
```

### Table: `vendor_client_assignments`

```sql
CREATE TABLE vendor_client_assignments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       uuid        NOT NULL REFERENCES vendors(id),
    client_id       uuid        NOT NULL REFERENCES clients(id),
    effective_from  timestamptz,                    -- null = "from creation"
    effective_to    timestamptz,                    -- null = "still active"
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,

    -- A row is considered "active" when effective_to IS NULL.
    -- The (vendor_id, client_id) pair may appear multiple times historically,
    -- but only ONE active row per pair at any time. Enforced as a partial unique index.
    CONSTRAINT vca_effective_interval_chk CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from
    )
);

CREATE UNIQUE INDEX vca_vendor_client_active_uq
    ON vendor_client_assignments (vendor_id, client_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_vca_vendor_id ON vendor_client_assignments (vendor_id);
CREATE INDEX idx_vca_client_id ON vendor_client_assignments (client_id);
```

### Flyway migration filename

`V1__create_clients_vendors_assignments.sql`, inside `order-service/src/main/resources/db/migration/`.

> `order-service` is **not yet Flyway-managed** as of this SDD's start (its `sync_db` counterpart `sync-service` started Flyway at `V1__create_users_and_refresh_tokens.sql` during the auth-foundation SDD; `order-service` is now joining the same convention). The `application.yml` of `order-service` will be updated to enable Flyway with `baseline-on-migrate=true` and `baseline-version=0`, so that the three newly-created tables are tracked while the legacy `orders`, `order_items`, and `products` tables (currently created by JPA `ddl-auto`) are baselined as pre-existing state.

---

## Risks

Re-evaluation of the explore risks against the locked decisions (original R-01..R-08 from `explore.md`, plus R-09..R-12 new).

| ID | Risk | Likelihood after decisions | Mitigation |
|----|------|----------------------------|------------|
| R-01 | FK constraint from `vendors.user_id` → `users.id` fails if the `users` table doesn't exist or `id` column type differs | **N/A** — no FK is declared cross-DB | None |
| R-02 | Dual-write consistency: app writes `Vendor` but doesn't update `User.vendorId` | **Resolved** (decision 1: `User.vendorId` is vestigial; no dual-write required) | Document the deprecation roadmap |
| R-03 | N:N assignment queries cause performance degradation at scale | **Low** — indexes on `(vendor_id)`, `(client_id)`, plus the partial `(vendor_id, client_id) WHERE effective_to IS NULL` index for the active-pairs lookup | Confirm index selectivity post-apply with representative volumes |
| R-04 | `Zone` or exclusivity logic needed before production use | **Deferred** (out of scope per decision 4) | Document as roadmap item |
| R-05 | `active` flag vs. `deleted_at` semantic confusion in queries | **Low** — `active` = business-operations flag; `deleted_at` = data-retention; default queries filter `deleted_at IS NULL` | Repository conventions documented in `sdd-spec` |
| R-06 | Soft-delete prevents order history retrieval for deactivated clients | **Low** — soft-delete is logical; `Order` rows remain readable | Confirm with business that historical orders remain accessible |
| R-07 | Naming conflict with existing `VendorContext` class | **None** — `VendorContext` lives in `com.sales.{sync,order}.auth.security`; new `Vendor` lives in `com.sales.order.model` | n/a |
| **R-08** | Cross-database integrity: a `User` is deleted/locked in `sync_db` but a `Vendor` row in `order_db` still references it | **Medium** — 1:1 invariant is service-layer enforced, not DB-enforced. A bad write leaves a stale `Vendor.user_id` row | `sdd-design` must pick the integrity mechanism (synchronous HTTP check vs. outbox reconciliation). Add a periodic reconciliation job in a follow-up. |
| **R-09** | `Order.clientId` and `Order.salespersonId` remain loose `String` after this change, with no referential integrity to `clients` or `vendors` tables | **Accepted v1 gap** (decision 4) | Document explicitly; `order-fk-migration` SDD is the remediation |
| **R-10** | `application.properties` (`ddl-auto=update`) and `application.yml` (`ddl-auto=none`) conflict | **Medium** — Spring Boot profile precedence decides which wins. Risk of Hibernate mutating the new tables at boot | `sdd-apply` must reconcile this conflict: remove the `.properties` override (the `.yml` should be the single source). Tests must pass against `ddl-auto=none` + Flyway. |
| **R-11** | Pre-existing tables `orders`, `order_items`, `products` already exist in `order_db`. Enabling Flyway with `baseline-on-migrate=true` creates a Flyway schema-history that says "baseline at V0" — meaning those tables are not versioned | **Low** — desired behaviour, but the legacy tables now diverge from any future Flyway-managed rebuild. Documented. | `sdd-apply` should include a one-time baseline migration that documents the legacy schema state for ops, but this is optional (Flyway tolerates `baselineVersion=0`). |
| **R-12** | Soft-deleting a `Client` does NOT cascade to `VendorClientAssignment`; assignments remain attached | **Low / by design** — assignments are historical business state and shouldn't vanish when a `Client` is soft-deleted. Reporting can still query "vendor X assigned to client Y, where Y is soft-deleted" for audit. | `sdd-spec` documents the convention. Hard-delete later (when `Order` FK migration lands) can be configured as `RESTRICT` to prevent orphaning. |

---

## Migration & rollback

### Forward migration

1. **`order-service/application.yml`** is updated to enable Flyway:
   ```yaml
   spring:
     flyway:
       enabled: true
       baseline-on-migrate: true
       baseline-version: 0
       locations: classpath:db/migration
   jpa:
     hibernate:
       ddl-auto: none
   ```
   The legacy `application.properties` line `spring.jpa.hibernate.ddl-auto=update` is removed in the same `sdd-apply` cycle to eliminate the conflict (R-10).
2. **Flyway acquires the schema lock** in `order_db` and runs `V1__create_clients_vendors_assignments.sql`.
3. **Tables created** in dependency order: `pgcrypto` extension → `clients` → `vendors` → `vendor_client_assignments`.
4. **`DatabaseInitializer`** (`com.sales.order.config.DatabaseInitializer`) is extended to seed at least one `Client` and one `Vendor` for local dev startup. Seed is idempotent and guarded by a unique identifier (`tax_id` for `Client`, `user_id` for `Vendor`). The `Vendor` seed requires an existing `User` row in `sync_db`; if none exists, the seed logs a warning and skips — no failure.
5. **Existing tests are unchanged** (`OrderServiceBootIT`, `ApplicationContextSmokeIT`). The new tables are created in `order_db` but no integration test queries them by default.

### Rollback

```sql
-- U1__drop_clients_vendors_assignments.sql (hand-written; not auto-generated)
BEGIN;

DROP TABLE IF EXISTS vendor_client_assignments;
DROP TABLE IF EXISTS vendors;
DROP TABLE IF EXISTS clients;
-- Optional: DROP EXTENSION IF EXISTS pgcrypto;  -- only if no other table needs it.

COMMIT;
```

**Rollback safety:** No existing data is mutated by the forward migration (the legacy `orders`, `order_items`, `products` tables are unaffected; only the new tables are created). The rollback script drops only the three new tables and is reversible.

**Note:** Rollback after `order-fk-migration` lands will require additional coordination and is outside the scope of this SDD.

---

## Success criteria

| # | Criterion | Testable by |
|---|-----------|-------------|
| SC-1 | `clients`, `vendors`, `vendor_client_assignments` tables exist in `order_db` with the column types, nullability, defaults, unique constraints, partial indexes, and FK constraints specified in §"Entity schemas". | `mvn -pl order-service test` succeeds; `psql \d clients \d vendors \d vendor_client_assignments` confirms structure |
| SC-2 | A `Client` can be linked to N `Vendor` rows via `VendorClientAssignment`; a `Vendor` can be linked to N `Client` rows. Trying to insert a second active `(vendorId, clientId)` row (both `effective_to IS NULL`) is rejected by the partial unique index. | `VendorClientAssignmentRepository` integration test |
| SC-3 | Soft-deleting a `Client` (setting `deleted_at = now()`) leaves its `VendorClientAssignment` rows intact. A query using the default repository convention (`findActiveById`) excludes the soft-deleted row, while a `findByIdIncludingDeleted` variant includes it. | `ClientRepository` integration test |
| SC-4 | `Order` endpoints (`POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/orders/catalog`) continue returning the same responses as before. `OrderServiceBootIT` and `ApplicationContextSmokeIT` pass without modification. | Existing integration test suite passes |
| SC-5 | A `Vendor` row can be created with a `user_id` value; the integrity check at the service layer rejects writes when `user_id` doesn't correspond to a known `User`. | `VendorRepository` integration test using a stub HTTP client for the sync-service verification call |
| SC-6 | Each entity has at least one integration test covering: create, read by ID, and soft-delete (where applicable). | Test suite under `src/test/java/com/sales/order/repository/` |
| SC-7 | The Migration & Rollback scripts described above apply and unapply cleanly against a fresh `order_db` (verified by a round-trip test in CI, not in this SDD apply). | Manual verification post-apply; round-trip CI is a follow-up |

---

## Non-goals

- Modifying `Order`, `OrderItem`, `Product`, or any existing controller in `order-service`.
- Migrating `Order.clientId` / `Order.salespersonId` from `String` to FK (deferred to `order-fk-migration`).
- Deprecating or removing the `User.vendorId` column in `sync_db.users`.
- Adding `Zone`, exclusivity, or product-category scoping to `VendorClientAssignment`.
- Adding pricing, payment terms, credit limit, or `price_list_id` to `Client`.
- GDPR `erased_at` column on any entity.
- Authentication/authorization changes in `sync-service` or the auth module.
- Multi-tenancy or workspace scoping (not in product scope).
- API endpoints (REST controllers) for `Client`, `Vendor`, or `VendorClientAssignment`. The entities exist; controllers come later in a follow-up SDD.

---

## Next recommended

After this proposal is accepted, proceed to **`sdd-spec`** with the following deliverables, in this order:

1. **`client-management`** capability spec (`openspec/specs/client-management/spec.md`):
   - CRUD lifecycle states (`draft → active → soft-deleted`).
   - Unique constraint on `tax_id`.
   - Default query conventions (`active = true AND deleted_at IS NULL` for listings; explicit `findByIdIncludingDeleted` for audit/admin paths).
   - Audit-field update conventions (`created_at` / `updated_at` set by `@PrePersist` / `@PreUpdate`; `created_by` / `updated_by` populated where an auth context is available — currently null).
   - Scenarios: create / read / list / soft-delete / uniqueness-violation errors.

2. **`vendor-management`** capability spec (`openspec/specs/vendor-management/spec.md`):
   - CRUD lifecycle.
   - 1:1 invariant with `User` (UNIQUE index + service-layer integrity check). Pick the integrity mechanism (synchronous HTTP vs. outbox) in `sdd-design`, then encode the rule here.
   - Query conventions for active vendors.
   - Scenarios: create / read / list / soft-delete / cross-DB integrity-failure path.

3. **`vendor-client-assignment`** capability spec (`openspec/specs/vendor-client-assignment/spec.md`):
   - Creation, expiration (setting `effective_to`), re-activation patterns.
   - Partial-unique-index interpretation: at most one active `(vendor, client)` pair at any time. Reactivation requires `effective_to` to be set on the prior row first.
   - Temporal queries: "which clients may this vendor serve right now?", "which vendor was assigned to this client on YYYY-MM-DD?".
   - Scenarios: create / expire / reactivate / active-vs-historical queries.

After `sdd-spec`, proceed to `sdd-design` (technical approach for the cross-DB integrity check, Flyway activation, and JPA mappings) and then `sdd-tasks` (TDD task breakdown with no `apply` execution — the apply phase lives in a future SDD).

---

## Notes on agent authoring

The proposal was first drafted by the `sdd-proposal` subagent, which reported an inability to read project files in its sandbox (a known limitation noted by the previous `sdd-explore` run as well). The parent orchestrator verified all technical facts against the actual codebase — including the package layout of `com.sales.order.model`, the existence of `sync_db` for `users`, the absence of a Flyway directory in `order-service`, the conflict between `application.properties` and `application.yml` regarding `ddl-auto`, and the PostgreSQL version (`postgres:15-alpine`). The corrections above reflect those verified facts and supersede the agent's first draft.
