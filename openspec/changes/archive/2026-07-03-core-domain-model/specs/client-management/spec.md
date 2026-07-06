# Client Management Specification

## Purpose

The Client Management capability owns the canonical `Client` record for the
order-service. A Client represents a business customer (typically a pharmacy
or similar establishment) that places orders and is serviced by one or more
Vendors. This capability is responsible for the lifecycle, identity,
soft-delete, and integrity constraints of the `Client` entity. Pricing,
payments, credit, and price-list concerns are explicitly out of scope and
are owned by separate, follow-up SDDs.

The Client entity is persisted in `order_db`. The lifecycle is exposed via a
REST API; request and response DTOs are owned by a follow-up SDD.

## Requirements

### Requirement: Client CRUD Lifecycle

The system MUST provide create, read-by-id, list (paginated and filterable),
partial update, soft-delete, and conditional hard-delete operations for the
`Client` entity. The hard-delete operation MUST succeed only when no Order
references the client and no active VendorClientAssignment exists for the
client; otherwise the system MUST refuse the operation with a domain-specific
error referencing the blocking relation.

#### Scenario: Create a new client

- GIVEN a valid Client request payload with a `tax_id` not used by any
  non-soft-deleted Client
- WHEN the system receives `POST /api/v1/clients`
- THEN a Client row is persisted with a generated UUID `id`, populated
  `created_at`/`updated_at`/`created_by`/`updated_by`, and the system returns
  `201 Created` with the persisted entity body

#### Scenario: Reject create with missing required fields

- GIVEN a Client request payload missing the `name` or `tax_id` field
- WHEN the system receives `POST /api/v1/clients`
- THEN the system rejects the request with a domain-specific validation error
  indicating the missing field

#### Scenario: Read client by id

- GIVEN an existing, non-soft-deleted Client `C`
- WHEN the system receives `GET /api/v1/clients/{id}` for `C.id`
- THEN the system returns `200 OK` with `C` as the response body

#### Scenario: Read soft-deleted client returns 404 by default

- GIVEN an existing Client `C` with `deleted_at != null`
- WHEN the system receives `GET /api/v1/clients/{id}` without
  `includeDeleted=true`
- THEN the system returns `404 Not Found`

#### Scenario: Read soft-deleted client returns 200 with includeDeleted

- GIVEN an existing Client `C` with `deleted_at != null`
- WHEN the system receives `GET /api/v1/clients/{id}?includeDeleted=true`
- THEN the system returns `200 OK` with `C` as the response body and
  `deleted_at` populated

#### Scenario: List clients with pagination

- GIVEN the system has `N` non-soft-deleted Clients
- WHEN the system receives `GET /api/v1/clients?page=0&size=20`
- THEN the system returns a paginated response containing `page`, `size`,
  `totalElements`, and a `content` array of Clients

#### Scenario: Partial update mutates only provided fields

- GIVEN an existing, non-soft-deleted Client `C`
- WHEN the system receives `PATCH /api/v1/clients/{id}` with only
  `phone = "+34 600 000 000"`
- THEN `C.phone` is updated, all other fields are unchanged, `updated_at` is
  refreshed, and `updated_by` is set from the authenticated principal

#### Scenario: Hard delete succeeds when no references exist

- GIVEN an existing Client `C` with no Order references and no active
  VendorClientAssignments
- WHEN an operator invokes the hard-delete operation for `C`
- THEN the row is removed from the database and the operation returns success

#### Scenario: Hard delete blocked by an Order reference

- GIVEN an existing Client `C` referenced by at least one Order
- WHEN an operator invokes the hard-delete operation for `C`
- THEN the system MUST refuse the operation and surface a domain-specific
  error referencing the blocking Order

#### Scenario: Hard delete blocked by an active assignment

- GIVEN an existing Client `C` with at least one active VendorClientAssignment
- WHEN an operator invokes the hard-delete operation for `C`
- THEN the system MUST refuse the operation and surface a domain-specific
  error referencing the blocking assignment

### Requirement: Tax ID Uniqueness

The system MUST enforce uniqueness of the `tax_id` column across all
non-soft-deleted Clients. A Client whose `deleted_at IS NOT NULL` MAY share a
`tax_id` with a new non-deleted Client. Uniqueness MUST be enforced at the
database level (a UNIQUE constraint or partial UNIQUE INDEX filtered by
`deleted_at IS NULL`).

#### Scenario: Reject duplicate tax_id on create

- GIVEN an existing, non-soft-deleted Client `C0` with `tax_id = "ABC123"`
- WHEN the system receives `POST /api/v1/clients` with `tax_id = "ABC123"`
- THEN the system rejects the create operation with a domain-specific
  uniqueness error

#### Scenario: Reject duplicate tax_id on update

- GIVEN two non-soft-deleted Clients `C1` and `C2` with distinct `tax_id`
  values
- WHEN the system receives `PATCH /api/v1/clients/{C1.id}` with
  `tax_id = C2.tax_id`
- THEN the system rejects the patch with a uniqueness error

#### Scenario: Soft-deleted tax_id may be reused

- GIVEN an existing Client `C0` with `tax_id = "ABC123"` and `deleted_at`
  set to `T1`
- WHEN the system receives `POST /api/v1/clients` with `tax_id = "ABC123"`
- THEN the system accepts the new Client because the previous holder is
  soft-deleted

### Requirement: Default Query Filter

Default list and read operations MUST filter to Clients where
`active = true AND deleted_at IS NULL`. An explicit `includeDeleted=true`
query parameter MAY opt the caller into receiving soft-deleted rows
alongside active rows. When `includeDeleted=true` is in effect, the response
MUST still distinguish active from soft-deleted rows by exposing `deleted_at`.

#### Scenario: Default filter excludes soft-deleted clients

- GIVEN the system has Client `A` (active, `deleted_at IS NULL`) and Client
  `B` (`deleted_at != null`)
- WHEN the system receives `GET /api/v1/clients` with no `includeDeleted`
- THEN only Client `A` is returned

#### Scenario: Explicit include includes both

- GIVEN the system has Client `A` (active) and Client `B` (soft-deleted)
- WHEN the system receives `GET /api/v1/clients?includeDeleted=true`
- THEN both `A` and `B` are returned, with `deleted_at` populated on `B`

### Requirement: Audit Field Semantics

The system MUST populate `created_at`, `updated_at`, `created_by`, and
`updated_by` for every Client row. `created_at` and `updated_at` MUST be set
automatically via JPA lifecycle callbacks. `created_by` and `updated_by`
MUST be set from the authenticated principal's user id (UUID) when known;
otherwise they MUST be `NULL`. The system MUST NOT persist sentinel values
such as `0` or `"system"` for `created_by`/`updated_by`.

#### Scenario: Timestamps populated on create

- GIVEN a create request arrives at time `T`
- WHEN the Client is persisted
- THEN `created_at = T`, `updated_at = T`, and the response reflects those
  values

#### Scenario: created_by defaults to null when no auth context

- GIVEN the request is processed before any authentication context is
  attached to the call
- WHEN the Client is persisted
- THEN `created_by IS NULL` and `updated_by IS NULL`

#### Scenario: updated_by set from authenticated principal

- GIVEN an authenticated principal with user id `U`
- WHEN `U` updates an existing Client
- THEN `updated_by = U.id` and `updated_at` is refreshed to the update time

### Requirement: Geographic Coordinate Validation

The system MUST accept `latitude` and `longitude` only within the canonical
ranges. `latitude` MUST satisfy `-90 <= latitude <= 90`. `longitude` MUST
satisfy `-180 <= longitude <= 180`. Out-of-range values MUST be rejected at
the API layer with a domain-specific validation error referencing the
offending coordinate and its expected range. Both fields are nullable; the
absence of either MUST be treated as "unknown location".

#### Scenario: Accept null coordinates

- GIVEN a Client request payload with `latitude` and `longitude` omitted
- WHEN the Client is persisted
- THEN both fields are `NULL` and the operation succeeds

#### Scenario: Reject out-of-range latitude

- GIVEN a Client request with `latitude = 91`
- WHEN the request is processed
- THEN the system rejects the operation with a validation error referencing
  the expected `[-90, 90]` range

#### Scenario: Reject out-of-range longitude

- GIVEN a Client request with `longitude = -181`
- WHEN the request is processed
- THEN the system rejects the operation with a validation error referencing
  the expected `[-180, 180]` range

### Requirement: Soft Delete Idempotence

Soft-deleting a Client MUST be idempotent. A second soft-delete on the same
Client MUST be a successful no-op (HTTP 204) and MUST NOT mutate `deleted_at`
or any other field. Soft-delete MUST set `deleted_at` to the current
timestamp only if it was previously `NULL`; otherwise the original
`deleted_at` MUST be preserved. Soft-delete MUST leave any active
VendorClientAssignment rows intact (see
`vendor-client-assignment#soft-delete-interactions`).

#### Scenario: Soft delete a fresh client

- GIVEN an existing non-deleted Client `C` with `deleted_at IS NULL`
- WHEN `DELETE /api/v1/clients/{id}` is invoked at time `T`
- THEN the response is `204 No Content`, `C.deleted_at = T`, and active
  assignments referencing `C` remain intact

#### Scenario: Soft delete is idempotent

- GIVEN a Client `C` with `deleted_at = T1`
- WHEN `DELETE /api/v1/clients/{id}` is invoked again at time `T2`
- THEN the response is `204 No Content` and `C.deleted_at` remains `T1`

### Requirement: API Surface (Endpoint Inventory)

The system MUST expose the following endpoints for Client Management. Request
and response DTOs are owned by a follow-up SDD; this change ships only the
endpoint inventory.

- `POST /api/v1/clients` — create
- `GET /api/v1/clients/{id}` — read (404 when not found, or soft-deleted
  without `includeDeleted=true`)
- `GET /api/v1/clients` — list, paginated
- `PATCH /api/v1/clients/{id}` — partial update
- `DELETE /api/v1/clients/{id}` — soft-delete (idempotent)

#### Scenario: Endpoint inventory routes correctly

- GIVEN the routing table of order-service
- WHEN an HTTP request arrives at any of the paths above with a matching
  method
- THEN the corresponding handler is invoked

### Requirement: Capability Out of Scope

The Client Management capability MUST NOT own Client pricing, payment terms,
credit limits, or price-list behavior. These concerns are deferred to a
separate pricing/payments SDD. The persisted schema MUST NOT include pricing
or payment columns.

#### Scenario: No pricing fields on Client

- GIVEN the persisted `clients` table
- WHEN the schema is inspected
- THEN no pricing, payment, credit, or price-list columns are present

## Cross-References

- See `vendor-management` for the Vendor lifecycle and the User 1:1
  invariant.
- See `vendor-client-assignment` for assignment lifecycle and soft-delete
  interactions.
