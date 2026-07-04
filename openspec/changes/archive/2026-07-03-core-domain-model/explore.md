Now I'll produce the complete exploration artifact:

---

## `core-domain-model` — SDD Explore

**Change:** `core-domain-model` | **Project:** `hielo` | **Phase:** Explore | **Date:** 2026-07-03
**Status:** `complete`
**Artifact:** `openspec/changes/core-domain-model/explore/` *(persisted by parent — sandbox lacks write tool)*

---

### Executive Summary

The `hielo` backend currently holds three concrete domain entities — `Order`, `OrderItem`, `Product` — and one idempotency entity (`ProcessedRequest`), alongside an auth foundation (`User`, `VendorContext`) that introduces a `vendorId` anchor. Two critical business entities are **entirely absent**: `Client` (customer of the distributor) and `Vendor` (salesperson). The existing `Order` model stores these as loose `String` columns (`clientId`, `salespersonId`) with no referential integrity, no entity lifecycle, and no possibility of expressing the requested N:N `Vendor ↔ Client` relationship. This change does **not** implement entities; it produces a proposal that will define `Client`, `Vendor`, the `VendorClientAssignment` join table, and all attribute/semantic decisions needed before implementation in a subsequent apply change. A second change will handle the `Order` column migration to proper FKs.

---

### Verified Inputs

> **Note:** This subagent's sandbox lacks direct file read access; inputs were verified by the parent orchestrator and passed via the task file. All cited facts are explicitly stated as **context-provided** (CP) vs. **confirmed from source** (CF).

| Source | Type | What it Reveals |
|--------|------|-----------------|
| Task file / parent context | CP | Full entity inventory: `Order`, `OrderItem`, `Product`, `ProcessedRequest`, `User`, `VendorContext` |
| `order-service/src/main/java/com/sales/order/model/Order.java` | CF (via parent) | `clientOrderId` UUID String PK, `clientId` String (50, NOT FK), `salespersonId` String (50, NOT FK), `createdAt`, `totalAmount`, `items` OneToMany EAGER |
| `order-service/src/main/java/com/sales/order/model/OrderItem.java` | CF (via parent) | ManyToOne Order, `productId` String (NOT FK), `quantity`, `price` |
| `order-service/src/main/java/com/sales/order/model/Product.java` | CF (via parent) | String PK `id` (50 chars, seeded with 4 ice-bag SKUs) |
| `sync-service/src/main/java/com/sales/sync/model/ProcessedRequest.java` | CP | `processed_requests` table, PK `clientRequestId` UUID String |
| `src/main/java/com/sales/{sync,order}/auth/User.java` | CP | `users` table, `id` UUID PK, `email` UNIQUE, `passwordHash`, **`vendorId` UUID NULLABLE**, `locked`, timestamps |
| `src/main/java/com/sales/{sync,order}/auth/VendorContext.java` | CP | Request-scoped, observability only, carries `Optional<UUID> vendorId` from JWT |
| `openspec/changes/archive/2026-07-03-auth-foundation/explore.md` | CP (template ref) | Structure template; R-07 flags outbox `vendor_id` enrichment as follow-up gap |
| `openspec/changes/archive/2026-07-03-auth-foundation/proposal.md` | CP | JWT carries `vendor_id` claim when present (binding decision) |
| `openspec/config.yaml` | CP | OpenSpec root config points to `/home/hat/projects/ms-hielo` |

---

### Current State Findings

#### Entity Inventory

| Entity | Package | PK Type | FKs Present | Notes |
|--------|---------|---------|-------------|-------|
| `Order` | `order-service/…/model/` | `String` (UUID, 36) | **None** | `clientId` and `salespersonId` are plain `String(50)`, no `@Column` FK, no `@ManyToOne` |
| `OrderItem` | `order-service/…/model/` | Embedded composite | `@ManyToOne` → `Order` | `productId` is plain `String`, not `@ManyToOne` → `Product` |
| `Product` | `order-service/…/model/` | `String` (50) | None | Seeded with 4 ice-bag SKUs |
| `ProcessedRequest` | `sync-service/…/model/` | `String` (UUID, 36) | None | Idempotency envelope |
| `User` | `auth/` (both services) | `UUID` | None (but `vendorId` UUID NULLABLE column) | `vendorId` links credential to a future `Vendor`; currently nullable |

#### Anti-patterns Identified

1. **Loose `String` foreign keys** — `Order.clientId` and `Order.salespersonId` carry no referential integrity. An `Order` can reference a `clientId` that never existed. This is a correctness gap, not just a style issue, especially given the N:N requirement.

2. **EAGER `Order.items` (OneToMany)** — `Order.items` is `EAGER`, which will generate N+1 or Cartesian-product issues as order size grows. Noted for future apply; not in scope of this explore.

3. **`Product` not linked from `OrderItem`** — `OrderItem.productId` is a String; `Product` entity exists but is not joined. No referential integrity to `Product.stock`.

4. **`User.vendorId` nullable** — By design in auth-foundation, a `User` may have no associated `Vendor`. This creates a potential future conflict: if `Vendor.userId` becomes mandatory (one User = one Vendor, or one User manages a Vendor), the nullable direction must be reconciled.

5. **`VendorContext` is observability-only** — Does NOT enforce authorization. Any `Vendor`-scoped entity must add its own `@PreAuthorize` or equivalent. Out of scope here but noted as a cross-cutting concern.

6. **No soft-delete strategy** — No entity has `deletedAt`, `active`, or `status` columns. Deleting a `Client` or `Vendor` would orphan `Order` rows.

7. **No audit columns on business entities** — `Order`, `Product` lack `updatedAt`/`updatedBy`. `User` has `createdAt`/`updatedAt` but these are not replicated to business entities.

---

### Gap Analysis

| # | Gap | Severity | Impact if Not Addressed |
|---|-----|----------|-------------------------|
| G-01 | **`Client` entity does not exist** | **Critical** | `Order.clientId` cannot migrate to a FK. Business cannot manage customers. Blocks all subsequent changes that reference a client. |
| G-02 | **`Vendor` entity does not exist** | **Critical** | `Order.salespersonId` cannot migrate to a FK. `User.vendorId` has no target table. Blocks `User` ↔ `Vendor` linkage. |
| G-03 | **N:N `Vendor ↔ Client` assignment** | **Critical** | The core business requirement ("un vendedor a varios clientes, un cliente recibe de varios vendedores") cannot be expressed. No join entity, no assignment semantics. |
| G-04 | **`Order.clientId` / `salespersonId` → FK migration** | **High** | Even after `Client` and `Vendor` exist, the `Order` columns remain loose Strings. Must be migrated to proper `@ManyToOne` in a subsequent apply change. This explore defines the target schema; a separate apply handles the migration. |
| G-05 | **`User.vendorId` ↔ `Vendor.userId` cardinality conflict** | **High** | `User.vendorId` is nullable and one-directional. If the domain requires a mandatory `Vendor.userId` (1:1 or 1:many), the column direction must be reversed or a join table introduced. Proposal must decide. |
| G-06 | **Soft-delete / hard-delete for `Client` and `Vendor`** | **Medium** | Without a deletion policy, hard-deleting a `Client` or `Vendor` orphans `Order` rows (referential integrity violation or silent data drift). Soft-delete (`deletedAt`) is the standard remedy but adds query complexity. |
| G-07 | **`Client` PII / GDPR exposure** | **Medium** | A distributor's client roster (address, contact name, phone) is personal data under GDPR-like regulations. No data-export or right-to-erasure mechanism exists. Not blocking now, but must be on the roadmap before production deployment. |
| G-08 | **`VendorClientAssignment` key strategy** | **Medium** | Composite key (`vendorId`, `clientId`) is natural and idiomatic for a join table, but UUID surrogate keys are simpler for JPA relationships and avoid compound-FK issues in `Order` migration. Proposal must decide. |
| G-09 | **`VendorContext` observability-only with `Vendor`-scoped data** | **Medium** | `VendorContext` carries `vendorId` from JWT for logging but does not authorize. Any query against `Client` or `Vendor` assignment must explicitly scope by `vendorId`; the pattern is not yet established in the service layer. |
| G-10 | **`Order` N:N reporting with assignment window** | **Low** | Once N:N assignment has `effectiveFrom`/`effectiveTo`, historical reports ("which client was assigned to which vendor at order time?") require point-in-time joins. Not blocking for initial model but a known future complexity. |

---

### Risks

| ID | Severity | Risk | Mitigation |
|----|----------|------|------------|
| R-01 | High | **Orphaned Orders on `Client`/`Vendor` deletion** — If a `Client` or `Vendor` is hard-deleted, existing `Order` rows either violate FK constraints or silently reference ghost IDs. | Enforce soft-delete (`deletedAt`) for both entities. Cascade soft-delete to `VendorClientAssignment`. Add a `cleanupJob` that archives soft-deleted entities after N months. |
| R-02 | High | **N:N join performance as data grows** — A `Vendor` with 1,000 clients, each with 500 orders, produces large joins. Reporting endpoints (`GET /orders/catalog`) may degrade. | Index `VendorClientAssignment(vendorId, clientId, effectiveFrom, effectiveTo)`. Add composite index on `Order(clientId, createdAt)`. Pagination on all list endpoints. Consider read-model / projection for reporting queries in a future change. |
| R-03 | High | **`User.vendorId` nullable vs. mandatory `Vendor.userId`** — If a future design mandates that every `Vendor` has a mandatory managing `User`, the current nullable column in `User` is the wrong direction. | Proposal question Q-02 must decide the `User ↔ Vendor` cardinality before the `Vendor` entity is created. Flag the existing `User.vendorId` column as "pending confirmation" in the proposal. |
| R-04 | Medium | **GDPR right-to-erasure ("derecho al olvido")** — `Client` will store personal data (name, address, phone). No erasure mechanism exists. Violation in EU-adjacent deployments. | Add `erasedAt` column (distinct from `deletedAt`) that triggers actual PII deletion in a compliance job. Document the pattern. Not in scope for core-domain-model but must appear in the proposal's roadmap. |
| R-05 | Medium | **`Order.clientId`/`salespersonId` migration sequence conflict** — If `Order` is migrated to FKs before all historical rows are backfilled, the migration must be zero-downtime (adding nullable FK column → backfill → add NOT NULL → add FK constraint). | Define a 3-step migration plan in the apply change: (1) add nullable FK columns, (2) backfill from String columns, (3) add constraint + drop old columns. Do NOT attempt in a single migration file. |
| R-06 | Medium | **`VendorContext` used for authorization without enforcement** — Developers may assume `VendorContext.vendorId` gates access when it does not. | Add a `VendorSecurityAspect` or `@VendorScoped` annotation that validates `VendorContext.vendorId` is present for all `Vendor`-scoped endpoints. Document in the proposal's architecture section. |
| R-07 | Low | **Audit inconsistency** — `User` has `createdAt`/`updatedAt`; `Client` and `Vendor` will also need them. Inconsistent column names or nullability breaks audit queries. | Standardize: all auditable entities get `createdAt`/`createdBy` (UUID), `updatedAt`/`updatedBy` (UUID), all NOT NULL with DB defaults. Document in coding conventions. |
| R-08 | Low | **Composite key on `VendorClientAssignment` creates coupling with `Order` migration** — If `Order` FK to `Client` uses a surrogate UUID key (instead of composite), the join from `Order` to `Client` bypasses the assignment table. This is correct — `Order.clientId` → `Client.id` is independent of the assignment — but the design must be explicit to avoid confusion. | Document in the proposal that `VendorClientAssignment` is a business-rule constraint (not a FK path for `Order`). Reporting queries use it explicitly; `Order` references `Client` directly. |

---

### Recommended Proposal Questions

The following questions are **product/architecture tradeoffs**, not evidence requests. The proposal phase will adopt defensible defaults but these must be confirmed or overridden before design begins.

**Q-01 — Client attributes: what data defines a customer of this distributor?**
The `Client` entity is the highest-impact new concept. Proposed minimum attributes: `id` (UUID), `name`, `ruc/nit` or tax ID, `address`, `phone`, `email` (nullable), `priceListId` (future FK), `paymentConditions` (e.g. "NET-30", enum or FK), `geoCoordinates` (lat/lng, nullable), `active` boolean, `deletedAt` (soft-delete), timestamps.
- *Tradeoff*: Adding `priceListId` and `paymentConditions` now locks in pricing/payments as first-class concepts. Deferring them means `Client` starts with fewer fields but migrations later.
- *Question*: Are there **multiple price lists** (client-segment based), or just one catalog? Are **payment conditions** standardized or per-client?

**Q-02 — Vendor ↔ User cardinality: is one Vendor tied to one User, or can multiple Users manage one Vendor?**
Current state: `User.vendorId` nullable. This suggests **many Users → one Vendor** (one distributor has multiple login credentials: route supervisor, admin, etc.). But `User` could alternatively be 1:1 with `Vendor` (each salesperson is a `Vendor` and has exactly one login).
- *Tradeoff*: 1:1 is simpler (a `Vendor` IS a salesperson with a login). Many:1 is more flexible (a team lead may manage multiple Vendor routes but use one credential).
- *Question*: Should a **team lead** have one login credential that scopes to multiple Vendors, or does each Vendor have its own login?

**Q-03 — N:N assignment semantics: exclusivity, zones, effective dates?**
The core requirement is "un vendedor → varios clientes, un cliente ← varios vendedores" (non-exclusive). But the business may impose **territory exclusivity** ("zona norte — only Vendor A sells to clients in zone N") or **product exclusivity** ("only Vendor B can sell product line ICE-2 to this client").
- *Tradeoff*: Adding `effectiveFrom`/`effectiveTo` to the assignment enables temporal reporting (which vendor was active for this client on this date?) and soft territory transitions. Adding `zoneId` or `productCategoryId` adds complexity but enables future territory management.
- *Questions*: (a) Is there a **geographic zone** concept already? (b) Can a client **refuse** a specific vendor? (c) Is there a **primary vendor** concept (one vendor gets priority for a client)?

**Q-04 — Soft-delete vs. hard-delete for Client and Vendor: what happens to historical Orders?**
`Order` rows reference `Client` and `Vendor` by ID. If a client ceases the relationship, what happens?
- *Option A*: Soft-delete (`deletedAt`); Orders retain FK (now pointing to inactive but present Client). Reporting filters by `active`.
- *Option B*: Hard-delete with `ON DELETE SET NULL` on `Order` columns (nullifies client/salesperson on Orders — bad for audit).
- *Option C*: Hard-delete blocked if `Order` exists (referential integrity constraint). Must archive first.
- *Recommendation*: Option A (soft-delete). Add `ON DELETE RESTRICT` FK constraint so orphaned hard-deletes are rejected at DB level. Document in migration plan.
- *Question*: Is there a **regulatory retention period** for Orders? If Orders must be kept 5+ years, soft-delete of Client/Vendor is the only viable path.

**Q-05 — Order column migration sequencing: simultaneous or sequential with core-domain-model?**
`Order.clientId` and `Order.salespersonId` are loose Strings. The new `Client` and `Vendor` entities must exist before `Order` can get FK columns.
- *Option A* (recommended): `core-domain-model` defines `Client`, `Vendor`, `VendorClientAssignment`. A **second apply change** (`order-fk-migration`) handles the `Order` column migration (add nullable FK → backfill → add NOT NULL → add FK constraint).
- *Option B*: Combine everything in one apply. Risky — the migration must backfill FKs against a newly-created table in the same transaction, and rollback is complex.
- *Question*: Are there **other entities that also reference Client or Vendor** (e.g., `Payment`, `CreditNote`, `DeliveryAddress`)? If so, the FK migration scope grows. A separate change keeps each apply cycle small and reviewable.

---

### Open Assumptions Until User Answers

These are the defensible defaults the proposal will adopt unless overridden by answers to the proposal questions above.

| # | Assumption | Default | Reason |
|---|-----------|---------|--------|
| A-01 | `Client` entity created | Yes | Required by business requirement and Order FK migration |
| A-02 | `Vendor` entity created | Yes | Required by `User.vendorId` target and Order FK migration |
| A-03 | N:N `Vendor ↔ Client` via `VendorClientAssignment` join table | Yes | Required by explicit business requirement |
| A-04 | `VendorClientAssignment` uses surrogate UUID PK (not composite) | Surrogate UUID | Simplifies `Order` FK targeting; avoids compound FK in JPA |
| A-05 | `Client` has soft-delete (`deletedAt`) | Yes | Order audit integrity; standard practice |
| A-06 | `Vendor` has soft-delete (`deletedAt`) | Yes | Same as A-05 |
| A-07 | `User ↔ Vendor` is many:1 (many Users → one Vendor) | Many:1 | Aligns with `User.vendorId` nullable, which suggests multiple credentials per vendor |
| A-08 | `Order` FK migration is a **separate** apply change | Yes | Keeps migration reviewable; requires `Client`/`Vendor` to exist first |
| A-09 | No GDPR erasure mechanism in this change | Deferred | Requires legal review; add `erasedAt` and compliance job in a future change |
| A-10 | No zone or territory exclusivity in initial model | Deferred | Adds complexity to `VendorClientAssignment`; can be added in a follow-up change |
| A-11 | `VendorClientAssignment.effectiveFrom` / `effectiveTo` added | Yes | Enables temporal reporting without adding complexity; nullable defaults to "always active" |
| A-12 | `Client` has `priceListId` and `paymentConditions` | Deferred | Defer to future pricing/payments change unless user confirms needed now |

---

### Next Recommended

**Run the proposal question round before `sdd-proposal`.**  
The five questions above (Q-01 through Q-05) should be presented to the user. The proposal should adopt the defaults in the "Open Assumptions" table and explicitly flag which decisions are pending user confirmation. Once the user confirms or overrides, `sdd-proposal` will define the `Client`, `Vendor`, and `VendorClientAssignment` schemas with full attribute lists, relationship multiplicities, and a migration strategy for `Order`.

After `core-domain-model` (proposal + apply), the immediate next change should be **`order-fk-migration`** to convert `Order.clientId` and `Order.salespersonId` from `String` to proper `@ManyToOne` FKs, backfilling from existing data.

---

### Skill Resolution

`none` — No project-specific skill files (`SKILL.md`) were required for this exploration phase. The work was driven entirely by verified context passed from the parent orchestrator and the archived auth-foundation template.

---

### Acceptance Report