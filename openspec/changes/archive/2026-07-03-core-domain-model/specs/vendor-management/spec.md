# Vendor Management Specification

## Purpose

The Vendor Management capability owns the canonical `Vendor` record for the
order-service. A Vendor is the operational counterpart of a User (a sales
agent or sales operator in the field) and is bound by a strict 1:1 invariant
to that User. Vendors service one or more Clients via
VendorClientAssignments; see the `vendor-client-assignment` capability.
Payroll, route management, and commission rules are explicitly out of scope.

The Vendor entity is persisted in `order_db`. The `User` entity is owned by
`sync-service` and lives in `sync_db`. Because PostgreSQL does not allow
cross-database foreign keys, the 1:1 invariant between `Vendor` and `User`
MUST be enforced at the service layer; the database only enforces a UNIQUE
INDEX on `vendors.user_id`. The exact integrity mechanism (synchronous HTTP
check, outbox-based reconciliation, or fallback) is documented in
`sdd-design`; this spec expresses only the behavior.

## Requirements

### Requirement: Vendor CRUD Lifecycle

The system MUST provide create, read-by-id, list (paginated and filterable),
partial update, soft-delete, and conditional hard-delete operations for the
`Vendor` entity. The hard-delete operation MUST succeed only when no Order
references the vendor; otherwise the system MUST refuse the operation with
a domain-specific error referencing the blocking relation. A Vendor MUST be
backed by a corresponding User in `sync_db`; see
`vendor-management#vendor-user-1-1-invariant`.

#### Scenario: Create a new vendor

- GIVEN a valid Vendor request with a `user_id` corresponding to an existing
  User in `sync_db` and not already linked to a non-soft-deleted Vendor
- WHEN the system receives `POST /api/v1/vendors`
- THEN the Vendor is persisted with a generated UUID `id`, populated audit
  fields, and the system returns `201 Created` with the persisted entity

#### Scenario: Read vendor by id

- GIVEN an existing, non-soft-deleted Vendor `V`
- WHEN `GET /api/v1/vendors/{id}` is invoked
- THEN the system returns `200 OK` with `V` as the response body

#### Scenario: Partial update mutates only provided fields

- GIVEN an existing, non-soft-deleted Vendor `V`
- WHEN `PATCH /api/v1/vendors/{id}` is invoked with only
  `display_name = "New name"`
- THEN `V.display_name` is updated, all other fields are unchanged,
  `updated_at` is refreshed, and `updated_by` is set from the authenticated
  principal

#### Scenario: Hard delete succeeds when no Orders reference the vendor

- GIVEN an existing Vendor `V` with no Order references
- WHEN an operator invokes the hard-delete operation for `V`
- THEN the row is removed and the operation returns success

#### Scenario: Hard delete blocked by an Order reference

- GIVEN an existing Vendor `V` referenced by at least one Order
- WHEN an operator invokes the hard-delete operation for `V`
- THEN the system MUST refuse the operation and surface a domain-specific
  error referencing the blocking Order

### Requirement: Vendor ↔ User 1:1 Invariant

The system MUST refuse to create or update a Vendor whose `user_id` does
not correspond to an existing User in `sync_db`. The system MUST refuse to
delete a User (in `sync_db`) that still has at least one non-soft-deleted
Vendor linked, and MUST surface this refusal as a domain exception until
the associated Vendor is soft-deleted. The exact implementation mechanism
(synchronous HTTP check, outbox-based reconciliation, or fallback) is
documented in `sdd-design`; the database does NOT enforce this invariant
due to the cross-database architecture.

#### Scenario: Reject vendor create with unknown user_id

- GIVEN a Vendor request with `user_id = U` where no User `U` exists in
  `sync_db`
- WHEN `POST /api/v1/vendors` is invoked
- THEN the system rejects the request with a domain-specific error
  indicating the unknown user

#### Scenario: Reject vendor update to unknown user_id

- GIVEN an existing Vendor `V` linked to User `U1`
- WHEN `PATCH /api/v1/vendors/{V.id}` is invoked with `user_id` changed to
  a non-existing User id
- THEN the system rejects the patch

#### Scenario: Reject user deletion with active vendor link

- GIVEN a User `U` in `sync_db` with at least one non-soft-deleted Vendor
  `V` linked via `V.user_id = U.id`
- WHEN a User deletion is attempted for `U`
- THEN the system refuses the deletion and surfaces a domain exception;
  the User remains undeleted until `V` is soft-deleted first

### Requirement: User ID Uniqueness

At most one non-soft-deleted Vendor per `user_id` MUST exist. The database
MUST enforce this via a UNIQUE INDEX on `vendors.user_id` (without an FK
due to the cross-database architecture; see
`vendor-management#vendor-user-1-1-invariant`). The system MUST reject any
attempt to create or update a Vendor to a `user_id` that is already linked
to another non-soft-deleted Vendor.

#### Scenario: Reject second vendor for the same user

- GIVEN an existing non-soft-deleted Vendor `V1` with `user_id = U`
- WHEN a new Vendor is created with `user_id = U`
- THEN the system rejects the create operation with a uniqueness error

#### Scenario: Soft-deleted vendor frees its user_id

- GIVEN an existing Vendor `V1` with `user_id = U` and `deleted_at != null`
- WHEN a new Vendor is created with `user_id = U`
- THEN the create operation succeeds because the previous link is
  soft-deleted

### Requirement: Default Query Filter

Default list and read operations MUST filter to Vendors where
`active = true AND deleted_at IS NULL`. Soft-deleted Vendors MUST be
excluded from default listings. An explicit `includeDeleted=true` query
parameter MAY opt the caller into receiving soft-deleted rows.

#### Scenario: Default filter excludes soft-deleted vendors

- GIVEN Vendor `A` (active, `deleted_at IS NULL`) and Vendor `B`
  (`deleted_at != null`)
- WHEN `GET /api/v1/vendors` is invoked with no `includeDeleted`
- THEN only Vendor `A` is returned

#### Scenario: Explicit include returns both

- GIVEN Vendor `A` (active) and Vendor `B` (soft-deleted)
- WHEN `GET /api/v1/vendors?includeDeleted=true`
- THEN both `A` and `B` are returned with `deleted_at` populated on `B`

### Requirement: Audit Field Semantics

The system MUST populate `created_at`, `updated_at`, `created_by`, and
`updated_by` for every Vendor row. `created_at` and `updated_at` MUST be set
automatically via JPA lifecycle callbacks. `created_by` and `updated_by`
MUST be set from the authenticated principal's user id (UUID) when known;
otherwise they MUST be `NULL`.

#### Scenario: Timestamps populated on create

- GIVEN a create request at time `T`
- WHEN the Vendor is persisted
- THEN `created_at = T`, `updated_at = T`

#### Scenario: Principal populates updated_by

- GIVEN an authenticated principal `U`
- WHEN `U` updates an existing Vendor
- THEN `updated_by = U.id` and `updated_at` is refreshed

### Requirement: Geographic Coordinate Validation

The system MUST accept `latitude` and `longitude` only within the canonical
ranges. `latitude` MUST satisfy `-90 <= latitude <= 90`. `longitude` MUST
satisfy `-180 <= longitude <= 180`. Out-of-range values MUST be rejected at
the API layer with a domain-specific validation error. Both fields are
nullable; the absence of either MUST be treated as "unknown location".

#### Scenario: Accept null coordinates

- GIVEN a Vendor request with `latitude` and `longitude` omitted
- WHEN the Vendor is persisted
- THEN both fields are `NULL`

#### Scenario: Reject out-of-range longitude

- GIVEN a Vendor request with `longitude = 181`
- WHEN the request is processed
- THEN the system rejects the operation with a validation error referencing
  the expected `[-180, 180]` range

### Requirement: Soft Delete Idempotence

Soft-deleting a Vendor MUST be idempotent. A second soft-delete on the same
Vendor MUST be a successful no-op (HTTP 204) and MUST NOT mutate `deleted_at`
or any other field. Soft-delete MUST leave any active VendorClientAssignment
rows intact for audit purposes (see
`vendor-client-assignment#soft-delete-interactions`).

#### Scenario: Soft delete is idempotent

- GIVEN Vendor `V` with `deleted_at = T1`
- WHEN `DELETE /api/v1/vendors/{V.id}` is invoked again at time `T2`
- THEN `V.deleted_at` remains `T1` and the response is `204 No Content`

### Requirement: API Surface (Endpoint Inventory)

The system MUST expose the following endpoints for Vendor Management.
Request and response DTOs are owned by a follow-up SDD.

- `POST /api/v1/vendors` — create (returns 422/409 if `user_id` is unknown
  or already linked to a non-soft-deleted Vendor)
- `GET /api/v1/vendors/{id}` — read
- `GET /api/v1/vendors` — list, paginated
- `PATCH /api/v1/vendors/{id}` — partial update
- `DELETE /api/v1/vendors/{id}` — soft-delete

#### Scenario: Endpoint inventory routes correctly

- GIVEN the routing table of order-service
- WHEN an HTTP request arrives at any of the paths above with a matching
  method
- THEN the corresponding handler is invoked

### Requirement: Capability Out of Scope

The Vendor Management capability MUST NOT own payroll, route management, or
commission rules. These concerns are deferred to a separate SDD. The
persisted schema MUST NOT include payroll, route, or commission columns.

#### Scenario: No payroll or commission fields on Vendor

- GIVEN the persisted `vendors` table
- WHEN the schema is inspected
- THEN no payroll, route, or commission columns are present

## Cross-References

- See `client-management` for the Client lifecycle and soft-delete
  semantics.
- See `vendor-client-assignment` for assignment lifecycle and soft-delete
  interactions.
- The auth-side User lifecycle is documented in `openspec/specs/auth/spec.md`
  and is treated as an upstream invariant here.
