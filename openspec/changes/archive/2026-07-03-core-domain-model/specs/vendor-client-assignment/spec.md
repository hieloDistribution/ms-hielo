# Vendor Client Assignment Specification

## Purpose

The Vendor Client Assignment capability owns the `VendorClientAssignment`
association between a Vendor and a Client. The association is a simple N:N
relation with temporal validity (`effective_from`, `effective_to`). It
expresses which Vendors may service which Clients at which points in time.
No primary-vendor flag, zone exclusivity, product scoping, or commission
calculation is owned here. The persisted schema is owned by order-service;
assignment endpoints are exposed under a versioned REST API.

## Requirements

### Requirement: Assignment Creation

The system MUST permit the creation of a `VendorClientAssignment` row
between a non-soft-deleted Vendor and a non-soft-deleted Client. The system
MUST reject an attempt to create an active assignment `(vendor_id, client_id)`
if one with `effective_to IS NULL` already exists for that pair. The database
MUST enforce this via a partial unique index on
`(vendor_id, client_id)` filtered by `effective_to IS NULL`. After a previous
assignment between the same pair has `effective_to` set, a new active
assignment for the same pair MUST be permitted.

#### Scenario: Create the first assignment

- GIVEN a non-soft-deleted Vendor `V` and Client `C` with no prior
  assignment between them
- WHEN `POST /api/v1/vendor-client-assignments` is invoked with
  `effective_from = null` and `effective_to = null`
- THEN the assignment is persisted with `effective_to IS NULL` (active) and
  the response is `201 Created`

#### Scenario: Reject duplicate active assignment

- GIVEN an existing active assignment `A` between `V` and `C` with
  `effective_to IS NULL`
- WHEN a new assignment is created between `V` and `C` with
  `effective_to IS NULL`
- THEN the system rejects the create operation with a domain-specific
  error indicating the active assignment conflict

#### Scenario: Permit new assignment after the previous one expired

- GIVEN an existing assignment `A0` between `V` and `C` with
  `effective_to = T1` (expired)
- WHEN a new assignment is created between `V` and `C` with
  `effective_to IS NULL`
- THEN the new assignment is persisted because the prior active row has
  been superseded

### Requirement: Assignment Expiration

The system MUST provide an operation that sets `effective_to = now()` on an
existing assignment, marking it expired. Expired assignments MUST NOT be
returned by "currently active" queries. Expiration MUST be idempotent: a
second expiration on the same assignment MUST be a successful no-op.

#### Scenario: Expire an active assignment

- GIVEN an active assignment `A` with `effective_to IS NULL`
- WHEN `PATCH /api/v1/vendor-client-assignments/{id}/expire` is invoked at
  time `T`
- THEN `A.effective_to = T` and `A` is no longer returned by
  currently-active queries

#### Scenario: Expire is idempotent

- GIVEN an assignment `A` with `effective_to = T1`
- WHEN `PATCH /api/v1/vendor-client-assignments/{id}/expire` is invoked
  again at `T2`
- THEN `A.effective_to` remains `T1` and the response is `200 OK`

### Requirement: Re-Activation Pattern

After expiration, the system MUST permit a fresh active assignment for the
same `(vendor_id, client_id)` pair, with the new assignment treated as the
canonical link going forward. Historical queries MUST continue to return
both rows.

#### Scenario: Re-assign after expire

- GIVEN an expired assignment `A1` between `V` and `C`
- WHEN a new assignment `A2` between `V` and `C` is created with
  `effective_to IS NULL`
- THEN `A2` is persisted and "currently active" queries return only `A2`

### Requirement: Temporal Queries

The system MUST provide the following temporal queries:

- "Currently active clients of a vendor" — assignments where
  `effective_to IS NULL`, the referenced Vendor is not soft-deleted, and
  the referenced Client is not soft-deleted.
- "Currently active vendors of a client" — mirror query.
- "Assignments including date X" — historical point-in-time query:
  `effective_from <= X AND (effective_to IS NULL OR effective_to >= X)`.

#### Scenario: List current clients of a vendor

- GIVEN Vendor `V` with two assignments `A1` (active) and `A2`
  (`effective_to = T1`, expired)
- WHEN `GET /api/v1/vendors/{V.id}/clients` is invoked
- THEN only `A1` is returned

#### Scenario: Point-in-time query returns the covering interval

- GIVEN assignments `A1` (`effective_from = T0`, `effective_to = T2`) and
  `A2` (`effective_from = T3`, `effective_to = null`) for the same `(V, C)`
- WHEN `GET /api/v1/vendor-client-assignments/at/{X}` is invoked with
  `X = T1` where `T0 < T1 < T2 < T3`
- THEN only `A1` is returned

#### Scenario: Point-in-time query returns an open-ended interval

- GIVEN an assignment `A` (`effective_from = T3`, `effective_to = null`)
- WHEN the at-timestamp endpoint is invoked with any `X >= T3`
- THEN `A` is returned

### Requirement: Soft Delete Interactions

Soft-deleting the referenced Vendor or Client MUST NOT delete the
assignment row itself; the assignment MUST remain queryable for audit
purposes (e.g., via the at-timestamp endpoint). The default "currently
active" queries (`/vendors/{id}/clients` and `/clients/{id}/vendors`)
MUST filter out assignments referencing a soft-deleted Vendor or Client.

#### Scenario: Vendor soft-deleted hides assignments from current view

- GIVEN an active assignment `A` referencing Vendor `V`
- WHEN `V` is soft-deleted
- THEN `GET /api/v1/vendors/{V.id}/clients` no longer returns `A`
- AND `A` remains queryable via the at-timestamp endpoint

#### Scenario: Client soft-deleted hides assignments from current view

- GIVEN an active assignment `A` referencing Client `C`
- WHEN `C` is soft-deleted
- THEN `GET /api/v1/clients/{C.id}/vendors` no longer returns `A`
- AND `A` remains queryable via the at-timestamp endpoint

### Requirement: Effective From Semantics

When `effective_from` is `NULL`, the assignment MUST be treated as effective
from the row's `created_at`. The check constraint
`effective_to >= effective_from` MUST hold when both fields are non-null;
the system MUST reject create or update requests that violate it with a
domain-specific validation error.

#### Scenario: Both null at creation is valid

- GIVEN a create request with `effective_from = null` and
  `effective_to = null`
- WHEN the assignment is persisted
- THEN the assignment is treated as effective from `created_at`

#### Scenario: Explicit effective_from is respected

- GIVEN a create request with `effective_from = X` and
  `effective_to = null`
- WHEN the assignment is persisted
- THEN the assignment is treated as effective from `X`

#### Scenario: Reject inverted range

- GIVEN a create request with `effective_from = T2` and
  `effective_to = T1` where `T1 < T2`
- WHEN the assignment is processed
- THEN the system rejects the request with a validation error

### Requirement: Audit Field Semantics

The system MUST populate `created_at`, `updated_at`, `created_by`, and
`updated_by` for every assignment row, identical to the conventions in
`client-management#audit-field-semantics` and
`vendor-management#audit-field-semantics`.

#### Scenario: Timestamps populated on create

- GIVEN a create request at time `T`
- WHEN the assignment is persisted
- THEN `created_at = T`, `updated_at = T`

### Requirement: API Surface (Endpoint Inventory)

The system MUST expose the following endpoints for Vendor Client Assignment.
Request and response DTOs are owned by a follow-up SDD.

- `POST /api/v1/vendor-client-assignments` — create
- `GET /api/v1/vendor-client-assignments/{id}` — read
- `GET /api/v1/vendors/{vendorId}/clients` — currently active clients of a
  vendor
- `GET /api/v1/clients/{clientId}/vendors` — currently active vendors of a
  client
- `GET /api/v1/vendor-client-assignments/at/{timestamp}` — historical
  point-in-time query
- `PATCH /api/v1/vendor-client-assignments/{id}/expire` — set
  `effective_to = now()`

#### Scenario: Endpoint inventory routes correctly

- GIVEN the routing table of order-service
- WHEN an HTTP request arrives at any of the paths above with a matching
  method
- THEN the corresponding handler is invoked

### Requirement: Capability Out of Scope

The Vendor Client Assignment capability MUST NOT own primary-vendor flags,
zone or territory rules, product-category restrictions, commission
calculations, or auto-renewal of expired assignments. The persisted schema
MUST NOT include any of these fields.

#### Scenario: No primary-vendor field

- GIVEN the persisted `vendor_client_assignments` table
- WHEN the schema is inspected
- THEN no `is_primary`, zone, territory, product, commission, or
  auto-renewal columns are present

## Cross-References

- See `client-management` for Client lifecycle and soft-delete semantics.
- See `vendor-management` for Vendor lifecycle and the User 1:1 invariant.
- The assignment entity does not enforce an FK to `clients` or `vendors`
  at the database level beyond what is required by the partial unique
  index on `(vendor_id, client_id) WHERE effective_to IS NULL`; referential
  integrity is enforced at the service layer.
