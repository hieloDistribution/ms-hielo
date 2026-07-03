# Auth Specification

> **Source-of-truth note** (added during `sdd-sync` of `auth-foundation`):
> this is the canonical auth spec, promoted from
> `openspec/changes/auth-foundation/specs/auth/spec.md` after sdd-verify
> reached PASS. Treat this file as the authoritative contract for
> authentication across both services. Subsequent changes that touch auth
> must (a) reference the requirements here by exact heading name in their
> delta spec, and (b) keep the scenario→implementation mapping fresh.

## Purpose

This specification defines the contract for the authentication subsystem that
spans the chained delivery of `auth-foundation-sync` and `auth-foundation-order`.
It describes what MUST be true once both change sets land, so that downstream
features (vendor ordering, reporting) can rely on stable behaviour without
re-reading the implementation.

Two services own this contract. The `sync-service` is the identity issuer and
authoritative store for users, password hashes, account-lock state, and refresh
token rows; it issues access tokens and rotates refresh tokens. The
`order-service` is a dependent resource server that validates bearer access
tokens issued by `sync-service`, populates a request `SecurityContext`, and
exposes a `VendorContext` derived from the `vendor_id` claim. Both services
MUST refuse to start when the JWT signing secret is missing or too short.

Cross-cutting authentication invariants — secret-strength checks, the rule that
the `vendor_id` claim is informational only — apply uniformly to both services.

### Token family model (locked decision)

A `token_family` is the per-user accountability boundary for refresh token
theft detection.

- The first time a user authenticates (no existing non-revoked family for
  that user), `sync-service` creates a new family.
- Subsequent logins by the same user (from any device) bind the new refresh
  token to that user's existing active family; they do NOT create a new
  family. Multi-device coexistence is therefore the default.
- A family is "burned" (every row marked `revoked=true`) when refresh theft
  is detected, or when an explicit logout-everywhere flow is added in a
  follow-up change. After burn, the next login starts a new family.

This is the only correct reading of the scenarios in this spec; any future
change that wants a different family policy MUST add an explicit delta.

## Requirements

### Requirement: Login With Valid Credentials Returns Access And Refresh Tokens

The `sync-service` MUST, on `POST /api/v1/auth/login` with a known user email
and the correct password, return HTTP 200 with a JSON body containing
`access_token`, `refresh_token`, and `expires_in` equal to 900 (seconds), and
MUST persist a new refresh token row that is not revoked.

The refresh token row MUST belong to the user's currently active token family
(the user's existing family if one exists without a `revoked=true` row; a
freshly created family otherwise).

#### Scenario: Login Succeeds For Known User With Correct Password

- GIVEN a user row exists with the submitted email and the stored password
  hash matches the submitted password
- WHEN the client calls `POST /api/v1/auth/login` with that email and
  password
- THEN the response status is 200
- AND the response body contains `access_token`, `refresh_token`, and
  `expires_in` equal to 900
- AND the refresh tokens table contains a new row for that user with
  `revoked` equal to false and a non-null `token_family`

#### Scenario: First Login For A User Without Any Active Family Creates A New Family

- GIVEN the user has no refresh token rows, OR every existing row has
  `revoked` equal to true
- WHEN the client calls `POST /api/v1/auth/login` with correct credentials
- THEN the response status is 200
- AND the inserted refresh token row has a freshly minted `token_family`
  UUID that did not exist before

#### Scenario: Subsequent Login For A User With An Active Family Joins That Family

- GIVEN the user has at least one refresh token row with `revoked` equal to
  false and a known `token_family` UUID
- WHEN the client calls `POST /api/v1/auth/login` with correct credentials
  from a different device than the one that produced that active row
- THEN the response status is 200
- AND the new refresh token row shares the same `token_family` UUID as the
  pre-existing active row

### Requirement: Login With Wrong Password Is Rejected Without Persisting A Refresh Token

The `sync-service` MUST, on `POST /api/v1/auth/login` with a known user email
and an incorrect password, return HTTP 401 and MUST NOT persist any refresh
token row for that login attempt.

#### Scenario: Login Fails When Password Does Not Match Stored Hash

- GIVEN a user row exists with the submitted email
- WHEN the client calls `POST /api/v1/auth/login` with that email and a
  password that does not match the stored hash
- THEN the response status is 401
- AND no new row is written to the refresh tokens table

### Requirement: Login On A Locked Account Is Rejected Regardless Of Password Correctness

The `sync-service` MUST, on `POST /api/v1/auth/login` when the target account
is locked, return HTTP 423 (Locked, per RFC 4918 §11.3) with a JSON body of
`{"error":"account_locked"}`, regardless of whether the supplied password is
correct, and MUST NOT persist any refresh token row.

> **Source-of-truth note** (added when this change was promoted from delta to
> canonical): the original draft of this scenario mandated HTTP 401 and the
> body literal `"Account locked"`. During `sdd-verify` we found that
> `design.md` (error mapping table) and the implementation both already
> returned HTTP 423 with `{"error":"account_locked"}`, and that HTTP 423 is
> the more accurate status code for a locked resource. The spec was aligned
> to design and code, not the other way around.

#### Scenario: Locked Account Returns Account Locked Error

- GIVEN a user row exists with the submitted email and the account is marked
  locked
- WHEN the client calls `POST /api/v1/auth/login` with that email and any
  password (correct or incorrect)
- THEN the response status is 423
- AND the response body equals `{"error":"account_locked"}`
- AND no new row is written to the refresh tokens table

### Requirement: Refresh Token Rotation Issues A New Token And Revokes The Presented One

The `sync-service` MUST, on `POST /api/v1/auth/refresh` presented with a
valid, unexpired, unrevoked refresh token, return HTTP 200 with a new refresh
token in the same token family as the presented token; the presented token row
MUST be marked revoked and a new refresh token row with `revoked=false` MUST
be persisted in the same family.

#### Scenario: Refresh Rotates The Presented Token Within Its Family

- GIVEN a refresh token row exists for the user with `revoked=false`,
  `expires_at` in the future, and a known `token_family`
- WHEN the client calls `POST /api/v1/auth/refresh` with that token
- THEN the response status is 200
- AND the response body contains a new `refresh_token`
- AND the presented token row has `revoked` equal to true
- AND the refresh tokens table has a new row with the same `token_family`
  and `revoked` equal to false

### Requirement: Refresh Token Replay Revokes The Entire Token Family

The `sync-service` MUST, on `POST /api/v1/auth/refresh` presented with a
refresh token whose row is already revoked, return HTTP 401 and MUST mark
every refresh token row sharing that row's `token_family` as revoked.

#### Scenario: Replay Of A Revoked Refresh Token Burns The Whole Family

- GIVEN a `token_family` has at least one refresh token row whose `revoked`
  is already true and at least one other row in the same family whose
  `revoked` is false
- WHEN the client calls `POST /api/v1/auth/refresh` with the already-revoked
  token
- THEN the response status is 401
- AND every refresh token row whose `token_family` matches the presented
  token has `revoked` equal to true

### Requirement: Refresh Token Past Its Expiry Is Rejected

The `sync-service` MUST, on `POST /api/v1/auth/refresh` presented with a
refresh token whose `expires_at` is in the past, return HTTP 401 and MUST NOT
persist a new refresh token row.

#### Scenario: Expired Refresh Token Is Rejected

- GIVEN a refresh token row exists for the user with `revoked=false` and
  `expires_at` earlier than the current server time
- WHEN the client calls `POST /api/v1/auth/refresh` with that token
- THEN the response status is 401
- AND no new row is written to the refresh tokens table

### Requirement: Refresh Token Rotation On One Device Does Not Affect A Sibling Device In The Same Family

The `sync-service` MUST, on `POST /api/v1/auth/refresh` presented with a
valid refresh token whose row is in a token family that also contains at
least one other unrevoked row, rotate only the presented row and MUST leave
the sibling row's `revoked` value unchanged.

#### Scenario: Device B Token Survives Device A Rotation In The Same Family

- GIVEN a `token_family` contains two unrevoked refresh token rows belonging
  to the same user (one bound to device A, one bound to device B)
- WHEN the client calls `POST /api/v1/auth/refresh` with the device A
  refresh token
- THEN the response status is 200
- AND the device A row's `revoked` is true
- AND a new row exists in the same `token_family` with `revoked=false`
- AND the device B row's `revoked` is unchanged (still false)

### Requirement: Order-Service Accepts A Valid Bearer Access Token And Populates Security Context

The `order-service` MUST, on any request whose `Authorization` header is
`Bearer <token>` where `<token>` is a structurally valid access token issued
by `sync-service`, signature-verified and unexpired, return the normal
business response with a populated `SecurityContext` whose user principal
carries the user identity from the token, and MUST expose that user's
`vendor_id` (when present in the token claims) via `VendorContext`.

#### Scenario: Valid Bearer Token Yields Populated Security Context And Vendor Context

- GIVEN an access token issued by `sync-service` for a user whose token
  claims include `vendor_id`
- WHEN the client sends any authenticated request to `order-service` with
  `Authorization: Bearer <token>`
- THEN the request is processed normally (not rejected as 401)
- AND the `SecurityContext` is populated with the user principal
- AND `VendorContext.exposeVendorId()` returns the same `vendor_id` value
  that was present in the token claims

### Requirement: Order-Service Rejects An Expired Bearer Access Token

The `order-service` MUST, on any request whose bearer access token's `exp`
claim is in the past, return HTTP 401 with an empty `SecurityContext`,
regardless of whether the signature is otherwise valid.

#### Scenario: Expired Access Token Is Rejected With Empty Security Context

- GIVEN an access token whose signature verifies against the configured
  signing secret but whose `exp` claim is earlier than the current server
  time
- WHEN the client sends any authenticated request to `order-service` with
  `Authorization: Bearer <token>`
- THEN the response status is 401
- AND the `SecurityContext` for that request is empty (no authenticated
  principal)

### Requirement: Order-Service Rejects A Tampered Bearer Access Token

The `order-service` MUST, on any request whose bearer access token does not
pass signature verification against the configured signing secret, return
HTTP 401, regardless of whether the `exp` claim is in the future and
regardless of any other header validity.

#### Scenario: Tampered Signature Is Rejected Even If Token Is Otherwise Well-Formed

- GIVEN an access token whose header and payload are structurally well-formed
  but whose signature has been altered and no longer verifies against the
  configured signing secret
- WHEN the client sends any authenticated request to `order-service` with
  `Authorization: Bearer <token>`
- THEN the response status is 401

### Requirement: Services Refuse To Start Without A Sufficient JWT Secret

The `sync-service` and the `order-service` MUST each refuse to start (fail
to bind and exit with a non-zero status) when the `JWT_SECRET` environment
variable is absent or, when present, has a length of fewer than 32 bytes.

#### Scenario: JWT Secret Missing Or Too Short Blocks Startup On Sync-Service

- GIVEN `JWT_SECRET` is unset, OR `JWT_SECRET` is set to a string of fewer
  than 32 bytes
- WHEN `sync-service` is started
- THEN `sync-service` MUST fail to bind
- AND the process MUST exit with a non-zero status
- AND `sync-service` MUST NOT have accepted any request

#### Scenario: JWT Secret Missing Or Too Short Blocks Startup On Order-Service

- GIVEN `JWT_SECRET` is unset, OR `JWT_SECRET` is set to a string of fewer
  than 32 bytes
- WHEN `order-service` is started
- THEN `order-service` MUST fail to bind
- AND the process MUST exit with a non-zero status
- AND `order-service` MUST NOT have accepted any request

### Requirement: Vendor Id Claim Is Informational And Never Authoritative

Both services MUST treat the `vendor_id` claim from an access token as
informational only. Code paths that make an authorization decision (for
example, "may this user act on this vendor's data") MUST re-query the
authoritative data source and MUST NOT gate the decision solely on the
`vendor_id` claim value. `VendorContext` MAY expose `vendor_id` for
display, logging, or query-hint purposes.

#### Scenario: Authorization Decision Re-Queries The Data Source And Does Not Trust The Claim Alone

- GIVEN an access token whose `vendor_id` claim names vendor V
- AND the authoritative data source records that the user is NOT authorised
  for vendor V
- WHEN any code path in either service is asked "may this user act on
  vendor V?"
- THEN the answer returned by that code path MUST be "no"
- AND the answer MUST NOT depend solely on the `vendor_id` claim value

## Notes

Ownership by chained PR:

- `auth-foundation-sync` owns: Login With Valid Credentials; Login With
  Wrong Password; Login On A Locked Account; Refresh Token Rotation Issues
  A New Token And Revokes The Presented One; Refresh Token Replay Revokes
  The Entire Token Family; Refresh Token Past Its Expiry Is Rejected;
  Refresh Token Rotation On One Device Does Not Affect A Sibling Device
  In The Same Family; the `sync-service` half of Services Refuse To Start
  Without A Sufficient JWT Secret; the `sync-service` half of Vendor Id
  Claim Is Informational And Never Authoritative.
- `auth-foundation-order` owns: Order-Service Accepts A Valid Bearer
  Access Token And Populates Security Context; Order-Service Rejects An
  Expired Bearer Access Token; Order-Service Rejects A Tampered Bearer
  Access Token; the `order-service` half of Services Refuse To Start
  Without A Sufficient JWT Secret; the `order-service` half of Vendor Id
  Claim Is Informational And Never Authoritative.

Scenario ordering matches the proposal's test plan: 1, 2, 3 (with family
scenarios), 4, 5, 6, 7, 8, 9, 10 (cross-cutting 11 and 12 last).
