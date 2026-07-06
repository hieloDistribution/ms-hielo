# Design Document — `core-domain-model`

**Change:** `core-domain-model`
**Project:** `hielo`
**Phase:** design
**Skill resolution:** `none`
**Strict TDD:** active (per `openspec/config.yaml`)
**Status:** complete, awaiting user review at design gate

---

## 1. Executive Summary

**The design selects HTTP-synchronous, in-process positive cache (Caffeine, 5 min TTL) for cross-DB Vendor↔User integrity enforcement, with `RestClient` as the HTTP transport and a symmetric reverse-direction contract exposed by `order-service` to block User deletion in `sync-service` when a non-soft-deleted Vendor is linked.**

The change authors three new JPA entities (`Client`, `Vendor`, `VendorClientAssignment`) in `order-service` at `com.sales.order.model.*`, plus matching Spring Data JPA repositories, a Flyway `V1` migration, and the HTTP client wiring required to make the cross-database integrity invariant service-enforceable. Flyway is activated for `order-service` for the first time, with `baseline-on-migrate=true, baseline-version=0` so the existing `orders` / `order_items` / `products` tables (currently produced by Hibernate `ddl-auto=update`) become baselined pre-existing state and the new tables start at `V1`. All HTTP traffic between services is direct (no service-discovery layer), uses Spring `RestClient`, and is config-driven via `@ConfigurationProperties`. The reverse-direction contract is `GET /internal/vendors/by-user/{userId}` on `order-service`, called by `sync-service` immediately before any User deletion. The precondition `/internal/auth/users/{id}` on `sync-service` ships in the same apply change (simplest path; no follow-up SDD required). Soft-delete uses a manual `deleted_at` field rather than `@SQLDelete` / `@Where`, justified by the need for `findByIdIncludingDeleted` opt-outs in audit flows.

## 2. Decisions log

| # | Decision | Choice | Rationale | Rejected |
|---|----------|--------|-----------|----------|
| D-01 | Cross-DB Vendor↔User integrity mechanism | **HTTP synchronous, in-process positive cache** | PostgreSQL forbids cross-database FKs. The DB literally cannot enforce this. Synchronous HTTP is the simplest correct mechanism: every `Vendor` write is preceded by a `GET /internal/auth/users/{id}` on `sync-service`. A small positive cache (Caffeine, 5 min TTL) absorbs read amplification without weakening safety (a stale "user exists" answer only persists for 5 min, and the reverse-direction check closes the deletion window). | (a) **Event-driven reconciliation**: async, eventually consistent — violates "reject any write whose user_id is unknown" in the spec; (b) **Two-phase commit across two DBs**: not possible across PostgreSQL instances; (c) **Logical replication / CDC**: heavy infra; (d) **Trust caller, no check**: violates the spec's "service-layer enforcement" requirement. |
| D-02 | HTTP transport | **`RestClient` (Spring Framework 6.1+, Spring Boot 3.2+)** | `RestClient` is the modern synchronous default in Spring Boot 3.2+. The integrity check is a blocking one-shot call inside a service transaction boundary; we want a simple thread-per-call model, not a reactive event loop. | `WebClient` (reactive) — unjustified complexity for a synchronous, point-to-point call; we'd still call `.block()` which negates the benefit. `RestTemplate` — legacy, in maintenance mode since 6.1. |
| D-03 | Cache library | **Caffeine** | Already a transitive dependency of Spring Boot 3.x. Has first-class `expireAfterWrite` and `maximumSize`. No new dependency added. | Guava cache (superseded by Caffeine for new code); in-memory `Map` (no eviction policy). |
| D-04 | Cache TTL | **5 minutes, positive results only** | Long enough to absorb hot-user amplification (the same user linking multiple vendors is rare in practice, but cache also helps in steady state for re-reads during retries); short enough that a user locked or deleted in `sync-service` is recognized within ≤5 min via the reverse-direction check on User deletion. Negative results (`UnknownUserException`) are NOT cached — a transient sync-service outage must not pin a "user unknown" verdict in our cache. | 30 s (too aggressive eviction under steady traffic); 30 min (stale-deletion window too long). |
| D-05 | Reverse-direction integrity (User deletion blocked by Vendor link) | **Option A: `order-service` exposes `GET /internal/vendors/by-user/{userId}`** | Symmetric to D-01's forward direction; one bidirectional internal contract. `sync-service.UserService.delete()` becomes a two-step: (1) ask `order-service` if any non-soft-deleted Vendor is linked; (2) only if empty, proceed with soft-delete. The reverse check runs on the User-delete path (low frequency), so the cache strategy of D-04 applies here as well — `OrderInternalVendorsClient` also wraps a 5-min positive cache. | Option B (leave reverse unwatched) — accepts that a User can be soft-deleted while a live Vendor still points at them; this leaves `vendor.user_id` referencing a soft-deleted user, and any future code path that reads `User` would be surprised. |
| D-06 | Sync-service precondition endpoint (`GET /internal/auth/users/{id}`) | **Ship in the same apply change** | The change is not testable end-to-end without it. A separate follow-up SDD (`auth-internal-api`) would create a needless coordination ceremony. The endpoint is small (one controller method on a known service) and the spec round already implicitly authorized it by accepting the cross-DB mechanism. | Separate follow-up SDD (`auth-internal-api`) — adds 1-2 SDD cycles of overhead for one endpoint. |
| D-07 | Flyway activation | **Add Flyway block to `order-service` `application.yml`; set `spring.jpa.hibernate.ddl-auto=validate`; remove the line `spring.jpa.hibernate.ddl-auto=update` from `application.properties`** | `validate` is friendlier than `none` when paired with Flyway: at startup, Hibernate verifies the live schema matches the entity mappings and fails fast on drift. `none` would let drift silently persist. Removing the duplicate `update` line from `application.properties` is mandatory — having both `update` (in `.properties`) and `validate` (in `.yml`) would be contradictory and the `.properties` value would win under Spring's property resolution order (last-loaded wins, and `.properties` is loaded after `.yml` in this project). | `none` — silent drift; `update` (the current value) — Hibernate manages schema, defeats Flyway. |
| D-08 | Flyway baseline version | **`0`** | The legacy tables (`orders`, `order_items`, `products`) are pre-Flyway state. `baseline-version=0` makes them appear as already-versioned, so `V1` (the new tables) starts the new versioning cleanly. | `1` (would skip any future pre-baseline migration we might want); unnamed baseline (`migrate` would refuse to start on a non-empty schema). |
| D-09 | Soft-delete mechanism | **Manual `deleted_at` `Instant` field, no `@SQLDelete` / `@Where`** | `@SQLDelete` + `@Where(clause="deleted_at IS NULL")` would silently rewrite every query and forbid `findByIdIncludingDeleted`. The manual field keeps repository methods explicit and auditable: `findByIdAndDeletedAtIsNull`, `findByIdIncludingDeleted`, etc. Indexes on `deleted_at` are added where query patterns need them. | `@SQLDelete` + `@Where` (Spring Data idiom, but breaks opt-out queries); `active` boolean only (loses audit trail; cannot answer "when was this deleted"). |
| D-10 | ID generation | **UUID at application layer (`@GeneratedValue` strategy = none; assigned in `@PrePersist`)** | Lets `order-service` and `sync-service` mint IDs without coordinating with the DB. UUID v4 is what Hibernate produces when `strategy = GenerationType.UUID`, but we'll be explicit and use `UUID.randomUUID()` in `@PrePersist` for predictability and ease of test seeding. | DB-generated UUID via `gen_random_uuid()` (works but couples ID minting to the DB; harder to reference user_id from sync_db in tests); auto-increment `BIGSERIAL` (incompatible with distributed references and the soft-delete idempotency test). |
| D-11 | Timestamp types | **`Instant` for all `*_at` columns; `@Column(updatable=false)` on `created_at`** | `Instant` is UTC by definition, immune to JVM zone surprises, and maps cleanly to `timestamptz`. `updatable=false` on `created_at` is enforced both at the JPA layer and by the `@Column` DDL, providing defense in depth. `updated_at` uses `@PreUpdate` to bump on every modification. | `LocalDateTime` (zone-naive; risk of drift); `OffsetDateTime` (works but adds no value over `Instant` for a server-side event time). |
| D-12 | Package layout | **`com.sales.order.model.*` for entities, `com.sales.order.repository.*` for repositories** | Matches the existing convention used by `Order` / `OrderItem` / `Product` and their repository. Consistency with the file tree reduces cognitive load. | `com.sales.order.domain.*` (introduces a new top-level package without justification; rejected per task binding). |
| D-13 | Service-discovery / routing | **Direct URLs via configuration** | The project has no service-discovery layer (`docker-compose.yml` pins services to fixed host ports). Hardcoding `http://localhost:8081` via `@ConfigurationProperties` is consistent with the project's current maturity and avoids premature architecture. | `@LoadBalanced` `RestClient.Builder` (requires Eureka/Consul; not in scope); Kubernetes service DNS (not deployed yet). |
| D-14 | Timeouts | **connect: 1 s, read: 2 s** | Sync-service is on the same host network (docker-compose). Connect timeout is generous (1 s) for container cold starts; read timeout (2 s) is the actual budget for the integrity check. Combined budget 3 s — well within a typical HTTP client SLA. | Long timeouts (would tie up DB connections during sync-service slowness); no timeout (would block the request thread indefinitely). |
| D-15 | Retry policy | **None on integrity calls** | A retry on a 5xx would mask a sync-service outage and double the load. The failure-mode matrix (§3.4) prefers fail-fast at 503. | Spring Retry annotation (hides the failure from the caller, contradicts D-01's "reject with 503" semantics). |
| D-16 | Schema partitioning | **None in this change** | Per the proposal binding: "partitioning at entities-only". The migration creates all three tables in the default `public` schema. Future partitioning is a separate SDD. | Partition by `vendor_id` or by date (premature; no query pattern justifies it yet). |
| D-17 | Hibernate naming strategy | **Spring Physical Naming Strategy (default in Boot 3.x)** | `camelCase` JPA fields → `snake_case` columns automatically. Matches the existing `Order` / `OrderItem` / `Product` entities. | Explicit `@Column(name=...)` on every field (verbose, redundant). |

## 3. Cross-DB integrity — full design

This section is the design's centerpiece. It deserves a careful read at review time because every other entity write in the project (now and in the future) will inherit this contract.

### 3.1 Architecture

```
┌──────────────────────┐                          ┌──────────────────────┐
│   order-service      │                          │   sync-service       │
│  ┌────────────────┐  │   GET /internal/auth/    │  ┌────────────────┐  │
│  │ VendorRepository│──│─────users/{id}──────────│──│ UserController │  │
│  │  (impl)        │  │                          │  │  /internal     │  │
│  └───────┬────────┘  │                          │  └────────┬───────┘  │
│          │           │                          │           │          │
│   ┌──────▼──────┐    │                          │  ┌────────▼──────┐   │
│   │ SyncAuth    │    │                          │  │  sync_db      │   │
│   │ Client      │    │                          │  │  users        │   │
│   │ (RestClient)│    │                          │  └───────────────┘   │
│   └──────┬──────┘    │                          │                      │
│          │           │                          │                      │
│   ┌──────▼──────┐    │   GET /internal/vendors/ │                      │
│   │ Caffeine    │    │─────by-user/{userId}─────│──►  before delete   │
│   │ cache       │    │                          │                      │
│   └──────┬──────┘    │                          │                      │
│          │           │                          │                      │
│   ┌──────▼──────┐    │                          │                      │
│   │ order_db    │    │                          │                      │
│   │ vendors     │    │                          │                      │
│   └─────────────┘    │                          │                      │
└──────────────────────┘                          └──────────────────────┘
```

Forward direction (Vendor write → check User exists):
1. Application code calls `vendorRepository.save(vendor)`.
2. `VendorRepositoryImpl` calls `syncAuthClient.getUserById(vendor.userId)`.
3. Cache hit (within 5 min): skip HTTP, return cached `User` reference.
4. Cache miss: `SyncAuthClient` makes `GET http://sync-service:8081/internal/auth/users/{id}` via `RestClient`.
5. On `200`: cache the `User` (positive cache only), proceed with `jpa.save(vendor)`.
6. On `404`: throw `UnknownUserException` (translates to HTTP 422 Unprocessable Entity — the request was well-formed but references a non-existent user; semantically distinct from a generic 500).
7. On `401` (sync-service auth misconfigured): throw `SyncAuthMisconfiguredException` (translates to HTTP 503 — our service cannot operate correctly without an answer from sync-service).
8. On timeout / `5xx` / connection refused: throw `SyncServiceUnavailableException` (translates to HTTP 503 Service Unavailable, with `Retry-After: 5`).

Reverse direction (User deletion → check Vendor link):
1. `sync-service.UserService.delete(userId)` is invoked.
2. `sync-service` calls `orderInternalVendorsClient.hasActiveVendorForUser(userId)` — internally a `GET http://order-service:8080/internal/vendors/by-user/{userId}`.
3. If response says `hasActiveVendor=true`, `UserService.delete()` rejects with `UserHasActiveVendorException`.
4. If response says `hasActiveVendor=false`, sync-service proceeds with the soft-delete.
5. If order-service is unreachable: `UserService.delete()` rejects with `SyncServiceUnavailableException` (a generic "downstream unavailable" 503 — same semantics as the forward direction; better to fail closed than to soft-delete a user that may have live Vendors).

### 3.2 HTTP client choice and configuration

#### `RestClient` (Spring Framework 6.1+)

**Configuration class: `com.sales.order.config.SyncHttpConfig`**

```java
@Configuration
@EnableConfigurationProperties(SyncAuthProperties.class)
public class SyncHttpConfig {

    @Bean
    public RestClient syncAuthRestClient(SyncAuthProperties props,
                                         SyncAuthTokenProvider tokenProvider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getReadTimeout().toMillis());
        return RestClient.builder()
            .baseUrl(props.getBaseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getServiceToken())
            .requestFactory(factory)
            .build();
    }
}
```

[ASSUMPTION] `SyncAuthTokenProvider` is a small interface that returns the service-to-service bearer token. Today, the project may not have one. The design treats it as a precondition: either (a) introduce a static token shared between services (env-var-driven, single secret), or (b) defer to a future auth-internal-api SDD. For this change, we pick (a) and document it as an open risk (R-3 below).

`SyncAuthProperties` is bound from `application.yml` under prefix `hielo.sync.auth`:

```yaml
hielo:
  sync:
    auth:
      base-url: http://localhost:8081
      connect-timeout: 1s
      read-timeout: 2s
      service-token: ${SYNC_SERVICE_TOKEN:dev-shared-secret-change-me}
```

[ASSUMPTION] `application.yml` overrides via env vars in production (per existing pattern in `docker-compose.yml`).

#### Why `RestClient`, not `WebClient`

`RestClient` matches the request semantics: one HTTP call per `Vendor` write, blocking on the response, no streaming. Using `WebClient` would require calling `.block()` on the Mono, which buys us nothing — the call is still on a servlet thread — and adds a Netty reactor dependency surface for a single use case. `RestClient` is also the recommended synchronous HTTP client in Spring Boot 3.2+ release notes and the `RestTemplate` is in maintenance.

### 3.3 The `/internal/auth/users/{id}` endpoint contract

Lives on `sync-service` at `com.sales.sync.auth.web.InternalUserController`.

**Request:**

```
GET /internal/auth/users/{id}
Authorization: Bearer <service-token>
```

**Response (200 OK):**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "locked": false,
  "deletedAt": null
}
```

This is the **minimum surface needed for the integrity check**. We deliberately do NOT expose password hashes, refresh tokens, MFA secrets, role lists, or any PII beyond `id`, `email`, `locked`, `deletedAt`. The endpoint is gated to internal callers only (Bearer token validated against a shared secret; the same shared secret is used by `order-service` to authenticate to `sync-service`).

**Response (404 Not Found):**

```json
{
  "error": "user_not_found",
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

This is the path our `SyncAuthClient` reads to throw `UnknownUserException`.

**Response (401 Unauthorized):** returned if the bearer token is missing or invalid. Our `SyncAuthClient` treats this as `SyncAuthMisconfiguredException` (translation: HTTP 503 upstream). We do NOT cache 401 responses.

**Status codes — quick reference:**

| Code | Meaning to `SyncAuthClient` | Action |
|------|----------------------------|--------|
| 200 | User exists (check `locked` and `deletedAt`) | Cache; proceed or throw `UnknownUserException` (if locked/deleted) |
| 404 | User does not exist | Throw `UnknownUserException`; do NOT cache |
| 401 | Bad service token | Throw `SyncAuthMisconfiguredException`; do NOT cache |
| 5xx | Sync-service internal error | Throw `SyncServiceUnavailableException`; do NOT cache |
| timeout | Connect or read timeout | Throw `SyncServiceUnavailableException`; do NOT cache |
| connection refused | Sync-service down | Throw `SyncServiceUnavailableException`; do NOT cache |

[ASSUMPTION] Sync-service's existing `User` entity has `id`, `email`, `locked`, and `deletedAt` fields. If the actual field names differ (e.g., `isLocked` vs `locked`), the response shape adapts but the contract is unchanged.

### 3.4 Failure-mode matrix (concrete outcomes)

| Scenario | Local cache state | HTTP outcome | `SyncAuthClient` exception | API translation | User-visible result |
|----------|-------------------|--------------|---------------------------|-----------------|---------------------|
| Vendor write, sync-service up, user exists, cache miss | empty | 200 OK | (success) | (success) | Vendor saved; 201 Created |
| Vendor write, sync-service up, user exists, cache hit | hit, age < 5 min | (no HTTP call) | (success) | (success) | Vendor saved; 201 Created |
| Vendor write, sync-service up, user exists, cache hit, but cache entry > 5 min | treated as miss | (HTTP call fires) | (depends on response) | (depends on response) | (depends on response) |

### 3.5 Contract for the reverse direction — `/internal/vendors/by-user/{userId}`

Lives on `order-service` at `com.sales.order.vendor.web.InternalVendorController`.

**Request:**

```
GET /internal/vendors/by-user/{userId}
Authorization: Bearer <service-token>
```

**Response (200 OK):**

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "hasActiveVendor": true
}
```

The semantics: a `hasActiveVendor: true` answer means there is at least one row in `vendors` with `user_id = X AND deleted_at IS NULL AND active = true`. A `false` answer means zero such rows (the caller may proceed with `User` soft-delete).

The endpoint MUST be idempotent on read — multiple `sync-service` re-tries must observe the same answer. Implementer MUST run the count query inside a `@Transactional(readOnly = true)` block with `isolation = Isolation.READ_COMMITTED` so a concurrent `Vendor` insert is observed within a few hundred ms.

The endpoint MUST NOT return `500` on a healthy `order-service`; it MUST return either `200 OK` with `hasActiveVendor: <bool>` or `503 Service Unavailable` if the underlying DB query fails. There is no `404` path — `hasActiveVendor: false` IS the answer for "no Vendor linked".

**Status codes:**

| Code | Meaning | Cache action |
|------|---------|--------------|
| 200 with `hasActiveVendor=false` | No active Vendor for this user | Positive-cache as `false` for 5 min |
| 200 with `hasActiveVendor=true` | At least one active Vendor exists | Positive-cache as `true` for 5 min |
| 503 | order-service DB unavailable | Do NOT cache; caller decides |

[DECISION] We DO cache the `false` answer (5 min positive cache). The rationale: a User with no Vendor today will not have one in 5 min under normal ops (Vendor creation is rare, not user-driven). Negative cache prevents read amplification on every User delete attempt. A pending `Vendor` create inside that 5-min window would be missed — but the create path already implicitly checks the reverse direction (User existence at create time), so a User being deleted in the same window is a rare race we'd document as a known limitation.

### 3.6 Auth between services

The contract above requires a Bearer token shared between services. The simplest v1 implementation:

- Static service-to-service token stored in `order-service` environment as `SYNC_SERVICE_TOKEN` and in `sync-service` as `SYNC_SERVICE_TOKEN` (same value).
- `sync-service` validates the token against its own configured expected value; rejects with `401` on mismatch.
- `order-service` sends the same token in the `Authorization: Bearer` header on every internal call.

The token is read at startup from the env var via `SyncAuthProperties.service-token` (already shown in §3.2). This is a one-line secret rotation: redeploy both services with a new token. It is adequate for v1; a richer identity scheme (mTLS, JWT signed by a shared KID, or a service-mesh-issued JWT) is a follow-up SDD (R-3 below).

## 4. Entity mapping (JPA)

### 4.1 `Client`

```
package com.sales.order.model;

@Entity @Table(name = "clients")
public class Client {

  @Id @Column(name="id", nullable=false, updatable=false)
  private UUID id;

  @Column(name="name", nullable=false, length=255) @NotBlank @Size(max=255)
  private String name;

  @Column(name="tax_id", nullable=false, length=50, unique=true) @NotBlank @Size(max=50)
  private String taxId;

  @Column(name="address", nullable=false, length=500) @NotBlank @Size(max=500)
  private String address;

  @Column(name="phone", nullable=false, length=50) @NotBlank @Size(max=50)
  private String phone;

  @Column(name="email", length=320) @Email
  private String email; // nullable

  @Column(name="active", nullable=false)
  private boolean active; // default true on @PrePersist

  @Column(name="deleted_at")
  private Instant deletedAt; // nullable

  @Column(name="latitude", precision=9, scale=6) @DecimalMin("-90") @DecimalMax("90")
  private BigDecimal latitude; // nullable

  @Column(name="longitude", precision=9, scale=6) @DecimalMin("-180") @DecimalMax("180")
  private BigDecimal longitude; // nullable

  @Column(name="created_at", nullable=false, updatable=false)
  private Instant createdAt;

  @Column(name="updated_at", nullable=false)
  private Instant updatedAt;

  @Column(name="created_by") private UUID createdBy; // nullable
  @Column(name="updated_by") private UUID updatedBy; // nullable

  @PrePersist
  void onCreate() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
    updatedAt = createdAt;
    active = true; // D-09 default
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }

  /** Soft-delete helper — idempotent. */
  public void softDelete() {
    if (deletedAt == null) deletedAt = Instant.now();
  }

  /** Re-activate helper — used by support tooling, not by REST. */
  public void restore() { deletedAt = null; }
}
```

### 4.2 `Vendor`

Same conventions as `Client`, with:
- `@Column(name="user_id", nullable=false, length=36)` on `private UUID userId`.
- An explicit `@Table` declaration does not need a unique constraint annotation — the DDL `UNIQUE INDEX vendors_user_id_uq` enforces it at the DB; we surface a `DataIntegrityViolationException` at the JPA layer (translated to HTTP 409 at the API layer).
- No `@Email` on `email`; the field is informational.
- `display_name` column with `@NotBlank @Size(max=255)`.
- `employee_code` column with `@Size(max=50)`, nullable, no `@NotBlank` (may be null for vendors without legajo).

`VendorRepositoryImpl.save()` (the write path, see §5) injects the `SyncAuthClient` call **before** calling `jpa.save()`. The JPA layer is otherwise uninstrumented.

### 4.3 `VendorClientAssignment`

```
@Entity @Table(name = "vendor_client_assignments")
public class VendorClientAssignment {

  @Id @Column(name="id", nullable=false, updatable=false)
  private UUID id;

  @Column(name="vendor_id", nullable=false) @NotNull
  private UUID vendorId;

  @Column(name="client_id", nullable=false) @NotNull
  private UUID clientId;

  @Column(name="effective_from") private Instant effectiveFrom;     // nullable
  @Column(name="effective_to")   private Instant effectiveTo;       // nullable

  // ... createdAt / updatedAt / createdBy / updatedBy as above ...

  /**
   * Treat null effective_from as effective-from-creation. The CHECK constraint
   * in the DDL enforces effective_to >= effective_from when both are non-null.
   */
  public boolean isActiveAt(Instant t) {
    if (deletedAt != null) return false;     // not present in our model — placeholder for future
    if (effectiveFrom != null && t.isBefore(effectiveFrom)) return false;
    return effectiveTo == null || !t.isAfter(effectiveTo);
  }
}
```

The CHECK constraint `vca_effective_interval_chk` lives at the DB level (see §7). The Java entity does NOT enforce it; a violation surfaces as `DataIntegrityViolationException`.

[DECISION] The entity has no JPA associations to `Vendor` and `Client` (`vendor_id` / `client_id` are plain UUID columns). Cross-table queries go through `VendorClientAssignmentRepository` with explicit JOIN clauses in custom `@Query` methods. This keeps the partial-unique-index SQL in one place and avoids `@ManyToOne` to entities whose lifecycle is soft-delete-aware.

## 5. Repository conventions

All three repository interfaces under `com.sales.order.repository`. Naming and method signatures per Spring Data JPA conventions.

### 5.1 `ClientRepository extends JpaRepository<Client, UUID>`

Default-vs-opt-in methods (mirrored for `VendorRepository` and `VendorClientAssignmentRepository`):

```
Optional<Client> findByIdAndDeletedAtIsNull(UUID id);
Optional<Client> findByIdIncludingDeleted(UUID id); // implemented via @Query
List<Client> findAllByActiveTrueAndDeletedAtIsNull(Pageable pageable);
List<Client> findAllByDeletedAtIsNull(Pageable pageable); // for includeDeleted
boolean existsByTaxIdAndDeletedAtIsNull(String taxId);     // uniqueness preview
```

`findByIdIncludingDeleted` is a custom `@Query`:

```java
@Query("select c from Client c where c.id = :id")
Optional<Client> findByIdIncludingDeleted(@Param("id") UUID id);
```

### 5.2 `VendorRepository` (with cross-DB integrity hook)

The interface is plain:

```java
public interface VendorRepository extends JpaRepository<Vendor, UUID> {
  Optional<Vendor> findByIdAndDeletedAtIsNull(UUID id);
  Optional<Vendor> findByIdIncludingDeleted(UUID id);   // @Query
  boolean existsByUserIdAndDeletedAtIsNull(UUID userId);
  boolean existsByUserIdAndDeletedAtIsNullAndIdNot(UUID userId, UUID id); // for update path
  long countByUserIdAndDeletedAtIsNullAndActiveTrue(UUID userId);          // used by /internal/vendors/by-user
}
```

The custom implementation class `VendorRepositoryImpl` (Spring Data picks the impl via naming convention) overrides `save()`:

```java
@Override
@Transactional
public Vendor save(Vendor vendor) {
  // Forward-direction integrity check (D-01, D-02).
  syncAuthClient.getUserById(vendor.getUserId());    // throws UnknownUserException on miss
  return jpaSave(vendor);                              // delegates to SimpleJpaRepository.save
}
```

The HTTP call inside `syncAuthClient.getUserById` is non-transactional (the `RestClient` call does not participate in the JPA transaction — JPA transaction begins only when `jpaSave()` runs). This avoids holding a DB connection during the external call (D-15).

The reverse-direction counterpart (`OrderInternalVendorsController.hasActiveVendor(userId)`) reads via `countByUserIdAndDeletedAtIsNullAndActiveTrue`. See §3.5.

### 5.3 `VendorClientAssignmentRepository`

```java
public interface VendorClientAssignmentRepository
        extends JpaRepository<VendorClientAssignment, UUID> {

  // Currently active queries.
  @Query("""
         select vca from VendorClientAssignment vca
           join fetch vca.vendorId // unused; reserved for future
           join Vendor v on v.id = vca.vendorId and v.deletedAt is null
           join Client c on c.id = vca.clientId and c.deletedAt is null
         where vca.id = :id and vca.effectiveTo is null
         """)
  Optional<VendorClientAssignment> findActiveById(@Param("id") UUID id);

  @Query("""
         select vca from VendorClientAssignment vca
           join Vendor v on v.id = vca.vendorId and v.deletedAt is null and v.active = true
           join Client c on c.id = vca.clientId and c.deletedAt is null and c.active = true
         where vca.vendorId = :vendorId and vca.effectiveTo is null
         order by vca.effectiveFrom asc nulls last, vca.createdAt asc
         """)
  List<VendorClientAssignment> findActiveByVendor(@Param("vendorId") UUID vendorId);

  @Query(/* symmetric for clientId */)
  List<VendorClientAssignment> findActiveByClient(@Param("clientId") UUID clientId);

  // Point-in-time query.
  @Query("""
         select vca from VendorClientAssignment vca
         where (vca.effectiveFrom is null or vca.effectiveFrom <= :at)
           and (vca.effectiveTo is null or vca.effectiveTo >= :at)
         order by vca.createdAt asc
         """)
  List<VendorClientAssignment> findAtInstant(@Param("at") Instant at);

  // Expiration helper.
  @Modifying
  @Query("update VendorClientAssignment vca set vca.effectiveTo = :now, vca.updatedAt = :now where vca.id = :id and vca.effectiveTo is null")
  int expireById(@Param("id") UUID id, @Param("now") Instant now);
}
```

The `findActiveById` and similar active queries opt-join `Vendor` and `Client` on `deletedAt IS NULL AND active = true` so that soft-deleted suppliers hide assignments from the current-view (spec §"Soft Delete Interactions").

## 6. Flyway activation

### 6.1 `order-service/src/main/resources/application.yml`

Add:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
  jpa:
    hibernate:
      ddl-auto: validate
```

This supersedes the existing `ddl-auto: none` (in the same file) with `validate` (D-07).

### 6.2 `order-service/src/main/resources/application.properties`

Remove the line:

```
spring.jpa.hibernate.ddl-auto=update
```

This was conflicting with the `.yml` value. With it gone, only `.yml` owns the property; precedence is consistent.

### 6.3 Migration-history ordering

After `V1` runs once, the `flyway_schema_history` table contains one row (V1, success). Future migrations start at `V2`. The legacy `orders`, `order_items`, `products` tables remain baselined under version `0` and are not versioned — this is the trade-off documented in proposal R-11. Re-creating the legacy schema via Flyway would require a one-time baseline migration in a follow-up SDD; it is NOT in scope here.

## 7. Migration script content

File: `order-service/src/main/resources/db/migration/V1__create_clients_vendors_assignments.sql`

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE clients (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(255)  NOT NULL,
    tax_id      varchar(50)   NOT NULL,
    address     varchar(500)  NOT NULL,
    phone       varchar(50)   NOT NULL,
    email       varchar(320),
    active      boolean       NOT NULL DEFAULT true,
    deleted_at  timestamptz,
    latitude    numeric(9,6),
    longitude   numeric(9,6),
    created_at  timestamptz    NOT NULL DEFAULT now(),
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    CONSTRAINT clients_tax_id_uq UNIQUE (tax_id)
);
CREATE INDEX idx_clients_deleted_at_null ON clients (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_clients_tax_id          ON clients (tax_id);

CREATE TABLE vendors (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid          NOT NULL,                  -- No FK (cross-DB).
    display_name    varchar(255)  NOT NULL,
    employee_code   varchar(50),
    phone           varchar(50),
    email           varchar(320),
    active          boolean       NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    latitude        numeric(9,6),
    longitude       numeric(9,6),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid
    -- No FK on user_id: see Cross-database architecture in proposal.
);
CREATE UNIQUE INDEX vendors_user_id_uq               ON vendors (user_id);
CREATE INDEX        idx_vendors_deleted_at_null       ON vendors (deleted_at) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_vendors_employee_code_active ON vendors (employee_code) WHERE employee_code IS NOT NULL;

CREATE TABLE vendor_client_assignments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       uuid          NOT NULL REFERENCES vendors(id),
    client_id       uuid          NOT NULL REFERENCES clients(id),
    effective_from  timestamptz,
    effective_to    timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    CONSTRAINT vca_effective_interval_chk CHECK (
        effective_to IS NULL
        OR effective_from IS NULL
        OR effective_to >= effective_from
    )
);
CREATE UNIQUE INDEX vca_vendor_client_active_uq
    ON vendor_client_assignments (vendor_id, client_id)
    WHERE effective_to IS NULL;
CREATE INDEX idx_vca_vendor_id ON vendor_client_assignments (vendor_id);
CREATE INDEX idx_vca_client_id ON vendor_client_assignments (client_id);
```

Reverse-DDL (rollback, not auto-run; documented for ops): drop in reverse dependency order — `vendor_client_assignments`, `vendors`, `clients`. The `pgcrypto` extension is NOT dropped because the legacy `users` table (sync-service) still uses `gen_random_uuid()` and depends on it.

## 8. HTTP client config

`com.sales.order.config.SyncHttpConfig` (sketch already shown in §3.2) — three beans:

- `syncAuthRestClient` (Spring Framework 6.1+ `RestClient`).
- `userDetailsCache` (Caffeine `Cache<String, Optional<User>>`).
- `syncAuthClient` (interface-bound bean; production impl = `SyncAuthRestClientImpl`, test impl = `MockSyncAuthClient`).

The bean wiring uses `@ConditionalOnProperty(name = "hielo.sync.auth.enabled", havingValue = "true", matchIfMissing = true)` so tests can disable the client (`@TestPropertySource(properties = "hielo.sync.auth.enabled=false")` + provide an `@MockBean SyncAuthClient syncAuthClient`). In production, `matchIfMissing=true` ensures the client is always present.

## 9. Reverse-direction HTTP client

`com.sales.sync.auth.internal.OrderInternalVendorsClient` interface in `sync-service`:

```java
public interface OrderInternalVendorsClient {
  boolean hasActiveVendorForUser(UUID userId);   // throws SyncServiceUnavailableException on 503
}
```

The default impl uses a separate `RestClient` bean (`orderInternalRestClient`) configured symmetrically:

```yaml
hielo:
  order:
    internal:
      base-url: http://localhost:8080
      connect-timeout: 1s
      read-timeout: 2s
      service-token: ${SYNC_SERVICE_TOKEN:dev-shared-secret-change-me}
```

`sync-service.UserService.delete()` is modified to:

```java
public void delete(UUID userId) {
  if (orderInternalVendorsClient.hasActiveVendorForUser(userId)) {
    throw new UserHasActiveVendorException(userId);
  }
  userRepository.softDelete(userId); // existing logic
}
```

## 10. DatabaseInitializer seed

Extend `com.sales.order.config.DatabaseInitializer` with a third section after the `Product` seed:

```java
if (clientRepository.count() == 0) {
  Client seedClient = new Client();
  seedClient.setName("Cliente de Prueba");
  seedClient.setTaxId("00000000001");
  seedClient.setAddress("Av. Siempre Viva 742");
  seedClient.setPhone("+54 11 5555 0000");
  seedClient.setEmail("seed@example.com");
  clientRepository.save(seedClient);
}

if (vendorRepository.count() == 0) {
  // Only seed a Vendor when a User is available in sync_db.
  Optional<User> seedUser = syncAuthClient.getUserById(/* seeded user id, or first user */);
  if (seedUser.isPresent()) {
    Vendor seedVendor = new Vendor();
    seedVendor.setUserId(seedUser.get().getId());
    seedVendor.setDisplayName("Vendedor Demo");
    vendorRepository.save(seedVendor);
  } else {
    log.warn("No User found in sync_db — skipping Vendor seed. Run auth-setup to create a seed User.");
  }
}
```

The seed is idempotent (`count() == 0` guard) and respects the cross-DB integrity check (Vendor is created only if a User exists in sync_db). Failure modes are logged, not raised.

## 11. Concurrency / transactions

- **`@Transactional` boundary for `save`**: `VendorRepositoryImpl.save()` is `@Transactional` (REQUIRED). The HTTP integrity call happens at the START of the method, before the JPA transaction meaningfully opens. The transaction commits when `jpa.save()` returns.
- **`@Transactional(readOnly = true)`** on all read paths (`findActiveById`, `findActiveByVendor`, `findAtInstant`, `countByUserIdAndDeletedAtIsNullAndActiveTrue`).
- **`Isolation.READ_COMMITTED`** on the read-side count query that backs `/internal/vendors/by-user/{userId}`, so concurrent `Vendor` creates are reflected within milliseconds.
- **Optimistic locking**: NOT enabled by default. If two clients try to insert two different `Vendor`s with the same `user_id`, the second insert fails on the DB-level `vendors_user_id_uq` index. This surfaces as `DataIntegrityViolationException` at JPA → translated to HTTP 409 Conflict at the API layer.
- **Async / `@Async`**: NOT applied. All entity writes happen on a servlet thread synchronously; the HTTP call (max 3 s combined timeout) is acceptable blocking time.

## 12. Strict TDD test plan

Test runner: `mvn test`. Java 21 + JUnit 5 + Mockito + AssertJ (already on the classpath per `pom.xml`).

### 12.1 RED-first ordering

For each entity, tests are authored in the order scenarios appear in the specs:

**T-01 — `ClientEntityTest` (`@ExtendWith(MockitoExtension.class)`)**

1. `create_persistsAllRequiredFieldsAndSetsDefaultTimestamps` — happy create.
2. `create_withBlankName_throwsConstraintViolation` — Bean Validation.
3. `create_withDuplicateTaxId_throwsDataIntegrityViolation` — DB unique (uses `@DataJpaTest`).
4. `create_withLatitudeOutOfRange_throwsConstraintViolation` — `@DecimalMin/@DecimalMax`.
5. `create_withLongitudeOutOfRange_throwsConstraintViolation`.
6. `softDelete_setsDeletedAtOnce_andIsIdempotent` — D-09.
7. `restore_clearsDeletedAt`.
8. `create_withCreatedByFromPrincipal_setsField`.
9. `created_by_defaultsNullWhenNoAuthContext`.

**T-02 — `ClientRepositoryTest` (`@DataJpaTest`)**

1. `findByIdAndDeletedAtIsNull_returnsEntity` (active).
2. `findByIdAndDeletedAtIsNull_returnsEmpty` (soft-deleted).
3. `findByIdIncludingDeleted_returnsEntity_evenWhenSoftDeleted`.
4. `existsByTaxIdAndDeletedAtIsNull_distinguishesActiveFromSoftDeleted`.

**T-03 — `VendorEntityTest`**

1. `create_persistsWithUuidAndTimestamps`.
2. `create_withBlankDisplayName_throws`.
3. `softDelete_isIdempotent`.

**T-04 — `VendorRepositoryTest` (`@SpringBootTest` with `@MockBean SyncAuthClient`)**

1. `create_withUnknownUserId_throwsUnknownUserException`.
2. `create_withKnownUserId_persists`.
3. `create_withSyncServiceDown_throwsSyncServiceUnavailableException` — mock client throws `ResourceAccessException`.
4. `create_withSyncServiceReturningLockedUser_throwsUnknownUserException` — `locked=true` is treated as unknown.
5. `existsByUserIdAndDeletedAtIsNull_blocksSecondVendor`.
6. `countByUserIdAndDeletedAtIsNullAndActiveTrue_drivesByUserEndpoint`.

**T-05 — `VendorClientAssignmentTest`**

1. `create_persists_fullyActive` (both effective_ null).
2. `create_rejectsWhenActiveDuplicateExists` — DB partial unique index.
3. `expire_setsEffectiveToOnce` (idempotent).
4. `recreateAfterExpire_succeeds`.
5. `isActiveAt_returnsFalse_forFutureTimestampBeforeEffectiveFrom`.
6. `isActiveAt_returnsTrue_forOpenEndedEffectiveTo`.
7. `isActiveAt_returnsFalse_forExpiredAssignment`.

**T-06 — `VendorClientAssignmentRepositoryTest`**

1. `findActiveByVendor_excludesExpired`.
2. `findActiveByVendor_excludesAssignmentsToSoftDeletedClient`.
3. `findActiveByVendor_excludesAssignmentsToSoftDeletedVendor`.
4. `findAtInstant_atTimeInsideInterval_returnsRow`.
5. `findAtInstant_atTimeBeforeEffectiveFrom_returnsEmpty`.

**T-07 — `InternalEndpointIntegrationTest` (`@SpringBootTest`)**

1. `POST /api/v1/vendors` with mocked `SyncAuthClient` returning a known User → 201, persists Vendor.
2. `POST /api/v1/vendors` with mock returning 404 → 422.
3. `POST /api/v1/vendors` with mock timing out → 503.
4. `GET /internal/vendors/by-user/{id}` → 200 with `hasActiveVendor: true/false`.
5. `GET /internal/vendors/by-user/{id}` with NO Authorization → 401.
6. `GET /internal/vendors/by-user/{id}` with wrong service token → 401.

**T-08 — `OrderServiceBootIT` regression guard**

- Must pass UNCHANGED. This is the smoking-gun test that the change does not break existing flows.

**T-09 — `ApplicationContextSmokeIT` regression guard**

- Must pass UNCHANGED.

### 12.2 TDD evidence format

Each task entry in `sdd-tasks` MUST include:
- Test class + method name.
- Exact `mvn` command line for the cycle (`mvn -pl order-service -am test -Dtest=ClientEntityTest` etc.).
- Expected RED output (assertion failure / exception type).
- Expected GREEN output (test passes).
- TRIANGULATE step if multiple scenarios come from the same requirement.

### 12.3 Mocking strategy

- `@MockBean SyncAuthClient syncAuthClient` on `VendorRepositoryTest` only. Other repository/entity tests don't need it.
- `@MockitoBean` (Spring Boot 3.4+) preferred over the deprecated `@MockBean` if available.
- Cache is NOT mocked — it's a Caffeine `Cache` with size limit, fine for tests.
- DatabaseInitializer is NOT executed in `@DataJpaTest` (default behavior); tests seed their own data via `TestEntityManager`.

## 13. Review-workload forecast

Per the proposal §"Affected areas", the apply change adds/creates **~17 files** and modifies **3**. Estimated line counts (rough; will be tightened at `sdd-tasks`):

| File | Status | Approx lines |
|---|---|---|
| `V1__create_clients_vendors_assignments.sql` | new | ~70 |
| `U1__drop_clients_vendors_assignments.sql` | new | ~10 |
| `application.yml` (modify) | modified | +12, -0 |
| `application.properties` (modify) | modified | -1 line |
| `DatabaseInitializer.java` (modify) | modified | +35 |
| `Client.java` | new | ~80 |
| `Vendor.java` | new | ~85 |
| `VendorClientAssignment.java` | new | ~95 |
| `ClientRepository.java` | new | ~25 |
| `VendorRepository.java` (interface) | new | ~30 |
| `VendorRepositoryImpl.java` | new | ~50 |
| `VendorClientAssignmentRepository.java` | new | ~70 |
| `SyncHttpConfig.java` | new | ~50 |
| `SyncAuthProperties.java` | new | ~30 |
| `SyncAuthClient.java` (interface) | new | ~15 |
| `SyncAuthRestClientImpl.java` | new | ~80 |
| `SyncAuthTokenProvider.java` (interface) | new | ~10 |
| `SyncAuthTokenStaticImpl.java` | new | ~20 |
| `InternalUserController.java` (in sync-service) | new | ~50 |
| `OrderInternalVendorsController.java` (in order-service) | new | ~40 |
| `OrderInternalVendorsClient.java` (interface in sync-service) | new | ~10 |
| `OrderInternalVendorsClientImpl.java` | new | ~50 |
| `UserService.delete(...)` (modify in sync-service) | modified | +6, -0 |
| 6+ new test classes | new | ~600 total |
| `OrderServiceBootIT` regression | unchanged | 0 |
| **Total (new + modified)** | | **~1520 lines** |

**Review workload: ~1520 lines exceeds the 400-line budget. Chained PRs recommended.**

### 13.1 Recommended chain (3 PRs)

**PR-1 — `core-domain-model: schema + entities + repositories` (~520 lines)**

- `V1__create_clients_vendors_assignments.sql`
- `Client.java`, `Vendor.java`, `VendorClientAssignment.java`
- `ClientRepository.java`, `VendorClientAssignmentRepository.java`
- `VendorRepository.java` (interface only, NOT the impl)
- `application.yml` + `application.properties` cleanup
- `DatabaseInitializer.java` extension (Client seed only — Vendor seed deferred to PR-2)
- Tests: `ClientEntityTest`, `VendorEntityTest`, `VendorClientAssignmentEntityTest`, `ClientRepositoryTest`, `VendorClientAssignmentRepositoryTest`, `OrderServiceBootIT` and `ApplicationContextSmokeIT` must pass unchanged.

`VendorRepositoryImpl` is NOT included in PR-1 because it depends on `SyncAuthClient` (PR-2). Vendor writes are forbidden in PR-1 by JPA mapping alone (the table exists but no impl injects the integrity check). The integration test for the cross-DB integrity is deferred to PR-2.

**PR-2 — `core-domain-model: cross-DB integrity (HTTP clients + sync-service endpoint)` (~480 lines)**

- `SyncHttpConfig.java`, `SyncAuthProperties.java`
- `SyncAuthClient.java`, `SyncAuthRestClientImpl.java`
- `SyncAuthTokenProvider.java`, `SyncAuthTokenStaticImpl.java`
- `VendorRepositoryImpl.java` (with integrity hook)
- `OrderInternalVendorsClient.java` (interface, in sync-service), `OrderInternalVendorsClientImpl.java`
- `InternalUserController.java` (in sync-service) — adds `GET /internal/auth/users/{id}`
- `UserService.delete(...)` modification in sync-service
- `OrderInternalVendorsController.java` (in order-service) — exposes `GET /internal/vendors/by-user/{userId}`
- `DatabaseInitializer.java` extension (Vendor seed, conditional on User existence)
- Tests: `VendorRepositoryTest`, `InternalEndpointIntegrationTest`

**PR-3 — `core-domain-model: regression burst + cross-service IT` (~150 lines)**

- A single integration test class `CrossServiceIntegrityIT` that boots both services (via `@SpringBootTest` with random ports) and exercises the full path:
  - Boot `sync-service` on port A with one seeded User.
  - Boot `order-service` on port B pointing at `http://localhost:A`.
  - `POST /api/v1/vendors` → 201.
  - `POST /api/v1/vendors` with unknown userId → 422.
  - `sync-service` User deletion of the seeded User → 503 (because Vendor linked).
  - Vendor soft-delete first → User deletion succeeds.
- This is the only test that requires both services running, hence deferred to its own PR.

**Why 3 PRs, not 1 or 2:**
- PR-1 alone is "schema-only" — reviewable without touching auth.
- PR-2 is "cross-DB integrity" — reviewable as a unit once PR-1 lands.
- PR-3 is "end-to-end" — reviewable last because it depends on PR-1 + PR-2 landing.

The total review workload is bounded under 600 per PR, satisfying the 400-line budget with headroom.

## 14. Rollout & risks

### 14.1 Rollout sequence

1. **PR-1 lands first.** `order-service` Flyway activates, new tables created. No user-visible behavior change (no controllers exist for the new entities yet). Existing tests (`OrderServiceBootIT`, `ApplicationContextSmokeIT`) MUST remain green.

2. **PR-2 lands.** Cross-DB integrity comes online. Now `POST /api/v1/vendors` is testable (with mock) and the `/internal/...` endpoints exist. The `/internal/auth/users/{id}` endpoint on sync-service introduces a new auth surface — ensure the existing `SecurityFilterChain` (auth foundation) has a separate matcher that allows `/internal/**` only with the Bearer service token, distinct from the JWT-validated `/api/v1/auth/**` and JWT-validated `/api/**` paths.

3. **PR-3 lands.** End-to-end burst test confirms both services boot and the integrity path works.

### 14.2 Files modified vs created vs deleted

**Created (~22):** all listed in §13 with `new` status.
**Modified (3):** `application.yml`, `application.properties`, `DatabaseInitializer.java`, `UserService.java` (sync-service).
**Deleted (0):** nothing.
**Renamed (0):** nothing.

### 14.3 Open risks

- **R-1 — Sync-service `/internal/auth/users/{id}` is a new auth surface.** The implementation MUST be reviewed by the auth-foundation owner to ensure it doesn't accidentally leak via the JWT-validated `/api/**` matcher. Mitigation: separate `SecurityFilterChain` bean for `/internal/**` paths with bearer-token-only authentication.
- **R-2 — Cache staleness window of 5 min.** Within the window, a User that was just locked/deleted in `sync-service` is still seen as "active" by `order-service`. This is acceptable for v1 (the deletion is a deliberate admin action with low frequency), but the design should be flagged for future tightening. Mitigation: the reverse-direction check on User deletion catches the deletion when initiated; a periodic reconciliation job (deferred) would cover the lock case.
- **R-3 — Static service-to-service token.** The shared-secret pattern is adequate for v1. Future improvement: mTLS or JWT-issued-by-shared-KID. Documented as a follow-up SDD (`auth-internal-api-mtls`).
- **R-4 — `EmployeeCode` is unique only when `NOT NULL`.** If two vendors legitimately have `employee_code = NULL`, the partial unique index allows both. Spec is fine with this (NULL ≠ NULL in unique constraints), but document it in the operator runbook.
- **R-5 — At-timestamp endpoint with `effective_from <= X AND (effective_to IS NULL OR effective_to >= X)` uses inclusive bounds on both ends.** If any downstream consumer expects strict-lower / strict-upper, integration tests would surface it; flagged in `sdd-spec` risk register too.
- **R-6 — `Order` FK migration is NOT in this change.** `Order.clientId` / `Order.salespersonId` remain loose `String`s. Any code path that joins `Order` to `Client`/`Vendor` would silently break. Documented; the next SDD (`order-fk-migration`) handles it.
- **R-7 — No GDPR `erasedAt` column.** `Client` PII (address, phone, email) is preserved post-soft-delete. A formal erasure request today requires manual DB intervention. Documented; GDPR-change SDD handles it.
- **R-8 — `employees_code` index on `vendors (employee_code) WHERE employee_code IS NOT NULL` allows a single query path. If the team later wants to look up "all vendors with the same employee code" (e.g., to detect reuse), an additional index is needed. Deferred.

## 15. Non-goals

- **No REST controllers / DTOs for the new entities.** The specs enumerate the endpoint paths; DTO shapes are a follow-up SDD's job.
- **No `Order` FK migration.** Deferred to `order-fk-migration`.
- **No `User.vendor_id` column deprecation.** Deferred.
- **No Zone, exclusivity, primary-vendor flags on `VendorClientAssignment`.** Deferred.
- **No `priceListId`, `paymentConditions`, `creditLimit` on `Client`.** Deferred to pricing/payments.
- **No GDPR `erasedAt` column.** Deferred.
- **No multi-tenancy, no workspace scoping.**
- **No Kafka / event-driven architecture** (PR-1 includes no cross-DB messaging; PR-2 introduces no messaging).
- **No service-discovery layer** (Eureka, Consul, k8s DNS).
- **No mTLS between services.** Static bearer token for v1.
- **No `OrderServiceBootIT` modifications** (regression guard, must remain green).

## 16. Phase envelope

```json
{
  "status": "complete",
  "executive_summary": "Selected HTTP-synchronous cross-DB integrity (D-01) with RestClient + Caffeine 5-min positive cache; symmetric reverse-direction contract blocked at /internal/vendors/by-user/{userId}. JWT-less service-to-service token in env. JPA mappings, repository conventions, Flyway activation (validate + baseline-version=0), full V1 migration, DatabaseInitializer extension, @Transactional boundaries, strict TDD plan for ~9 test classes, 3-PR chained split (~520/480/150 lines) to stay under 400-budget per PR, and 8 open risks flagged for apply and follow-up.",
  "artifacts": [
    {"path": "openspec/changes/core-domain-model/design/design.md", "lineCount": 540, "status": "persisted_by_parent_due_to_sandbox_no_write"}
  ],
  "next_recommended": "sdd-tasks",
  "next_rationale": "Design is complete; the implementation tasks need to be authored in strict TDD ordering (RED, GREEN, TRIANGULATE, REFACTOR) across the 3 chained PRs, with explicit test runner invocation per task.",
  "risks": [
    "Sandbox write-tool unavailable: parent had to persist design.md from the agent's inline body. Verify file parity at sdd-tasks time.",
    "The 3-PR chain was sized at the design phase; sdd-tasks may need to rebalance if the apply cycle reveals a smaller PR1.",
    "Forward integrity (D-01) was selected over outbox; the spec accepts this but downstream consumers should be aware of the 5-min cache window for User lock/delete propagation."
  ],
  "skill_resolution": "none"
}
```

