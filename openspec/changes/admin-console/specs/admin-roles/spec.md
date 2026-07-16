# Admin Roles Specification (delta)

> Source-of-truth delta for change `admin-console`. This document is a delta on top of the canonical auth spec at `openspec/specs/auth/spec.md`. It MUST be read together with that spec and the sister delta `specs/admin-bootstrap/spec.md` in the same change. Anything not redefined here inherits the canonical contract.

## Purpose

This delta defines the administrative role lifecycle: how `ADMIN` and `REPARTIDOR` are granted, revoked, and audited, the `/api/v1/admin/**` endpoint contract, and the closure of the open-signup role bypass. It does not redefine login, refresh, token families, or `vendor_id` semantics — those remain fully owned by the canonical auth spec.

The two services both continue to refuse to start when `JWT_SECRET` is missing or shorter than 32 bytes; that invariant is unchanged.

The role vocabulary is fixed for this delta: `ADMIN`, `REPARTIDOR`, `CLIENT`. A user MAY hold more than one role at a time (e.g. an admin who is also a client in their own system). New roles added in future changes require an explicit delta.

### Authority rule for elevated actions

A role claim contained in a JWT is authoritative for the **gate** (it controls whether the request reaches the controller at all), but it is NOT authoritative for **specific elevated operations** — those operations MUST re-query the database for the user's current role state and `active` flag before granting the privilege. This is consistent with the canonical "informational-only" rule for `vendor_id`, applied symmetrically to `roles`.

This is the only correct reading of the scenarios below; any future change that wants different authority semantics MUST add an explicit delta.

## Requirements

### Requirement: Public Signup Ignores Client-Supplied Role And Defaults To CLIENT

The `sync-service`, on `POST /api/v1/auth/signup` for an unauthenticated request, MUST accept only the fields `email`, `password`, `full_name`, and (for client self-registration of a business) the optional business profile fields. Any `role`, `roles`, `is_admin`, or `tipo` field present in the request body MUST be ignored; the created user row MUST always carry `roles` containing `CLIENT` (only) and `active=true`. The ignored-field event MUST be recorded in `admin_audit_log` with `action='signup_role_ignored'`, the requested email, and the field name(s) the client tried to set.

#### Scenario: Signup With role=ADMIN In Body Creates a CLIENT User

- GIVEN the request `POST /api/v1/auth/signup` has body `{ "email":"new@x.com", "password":"...", "full_name":"New", "role":"ADMIN" }`
- WHEN the service processes the request
- THEN the response status is 201
- AND the created `users` row has `roles` containing exactly `["CLIENT"]`, NOT `["ADMIN"]`
- AND an audit log row exists with `action='signup_role_ignored'`, `actor_user_id=null`, `target_email='new@x.com'`, and `notes='role=ADMIN'`

#### Scenario: Signup Without Any role Field Creates a CLIENT User

- GIVEN a clean signup request with no `role` field
- WHEN the service processes the request
- THEN the user is created with `roles` containing `["CLIENT"]`
- AND no audit log row is created (the ignored-field path was not exercised)

### Requirement: Admin Endpoints Are Gated By ADMIN Role In JWT And Active Status In Database

The `order-service` MUST reject any request to a path under `/api/v1/admin/**` whose:

1. Bearer access token does not contain `roles` containing `ADMIN` — return HTTP 403 with body `{"error":"admin_role_required"}`.
2. Bearer access token contains `roles` containing `ADMIN` BUT the corresponding `users` row is `active=false` or no longer has `roles` containing `ADMIN` in the database — return HTTP 403 with body `{"error":"admin_role_required"}`. (This is the "re-query" half of the authority rule.)
3. Bearer access token contains `roles` containing `ADMIN` AND the user is active with `roles` containing `ADMIN` — proceed.

This requirement overrides `SecurityConfig`'s blanket `anyRequest().authenticated()` only for admin paths; for all other paths the canonical auth spec continues to apply unchanged.

#### Scenario: Non-Admin Caller Receives 403

- GIVEN an access token whose claims include `roles: ["CLIENT"]` (no ADMIN)
- WHEN the client calls `GET /api/v1/admin/users`
- THEN the response status is 403
- AND the response body equals `{"error":"admin_role_required"}`

#### Scenario: Admin Caller Whose Database Row Was Deactivated Receives 403

- GIVEN an access token whose claims include `roles: ["ADMIN"]`
- AND the corresponding `users` row has `active=false` in the database (e.g. an admin was just deactivated by another admin and the caller's token is still valid)
- WHEN the client calls `GET /api/v1/admin/users`
- THEN the response status is 403
- AND the response body equals `{"error":"admin_role_required"}`

#### Scenario: Active Admin Caller Is Permitted

- GIVEN an access token whose claims include `roles: ["ADMIN"]`
- AND the corresponding `users` row has `active=true` and `roles` containing `ADMIN` in the database
- WHEN the client calls `GET /api/v1/admin/users`
- THEN the response is processed normally (not rejected by the gate)

### Requirement: Admin Can List Users With Filter And Search

The `order-service`, on `GET /api/v1/admin/users?role=ADMIN&q=juan&page=1&page_size=20` from an active admin, MUST return HTTP 200 with a JSON body of `{"items":[...], "total": N, "page": 1, "page_size": 20}` where each item includes at minimum `id`, `email`, `full_name`, `roles`, `active`, `created_at`, `last_login_at`. The list MUST include deactivated users (with `active=false`) flagged accordingly; the gate is responsible for filtering out admins viewing anonymous data, not the listing endpoint itself.

`role` and `q` are optional. `q` matches across `email` and `full_name` (case-insensitive, substring).

#### Scenario: Listing Returns Matching Users

- GIVEN an active admin caller
- AND the database has users `{id:1, email:"juan@x.com", roles:["REPARTIDOR"], active:true}`, `{id:2, email:"ana@y.com", roles:["CLIENT"], active:true}`, `{id:3, email:"juan.admin@x.com", roles:["ADMIN"], active:false}`
- WHEN the client calls `GET /api/v1/admin/users?q=juan`
- THEN the response status is 200
- AND the body contains `items[0].id` equal to 1 OR 3 (depending on order), but never 2
- AND the deactivated admin (id 3) appears with `active=false`

#### Scenario: Listing Without Filters Returns All Paginated

- GIVEN an active admin caller
- AND the database has 25 users
- WHEN the client calls `GET /api/v1/admin/users?page=2&page_size=10`
- THEN the response status is 200
- AND the body contains exactly 10 items in `items`
- AND `total` equals 25

### Requirement: Admin Can Change Another User's Roles With Audit Trail

The `order-service`, on `PATCH /api/v1/admin/users/{id}/roles` from an active admin, MUST accept a body `{"roles": ["ADMIN", "REPARTIDOR"]}` and replace the target user's `roles` array with the supplied list. The endpoint MUST refuse the request with HTTP 409 and body `{"error":"cannot_self_demote_last_admin"}` if the target user is the caller AND the operation would remove `ADMIN` AND the caller is the only `users` row with `active=true` and `roles` containing `ADMIN`.

#### Scenario: Adding REPARTIDOR To A Client User Succeeds

- GIVEN an active admin caller (id 99)
- AND a target user (id 5) with `roles` containing `["CLIENT"]`
- WHEN the admin calls `PATCH /api/v1/admin/users/5/roles` with body `{"roles": ["REPARTIDOR"]}`
- THEN the response status is 200
- AND `users` row id 5 now has `roles` containing `["REPARTIDOR"]`
- AND an audit log row exists with `actor_user_id=99`, `action='roles_changed'`, `target_user_id=5`, `before=['CLIENT']`, `after=['REPARTIDOR']`, and a generated `request_id`

#### Scenario: Self-Demote By The Sole Admin Is Blocked

- GIVEN exactly one active admin exists, that admin is also the caller (id 99)
- WHEN the admin calls `PATCH /api/v1/admin/users/99/roles` with body `{"roles": ["CLIENT"]}`
- THEN the response status is 409
- AND the response body equals `{"error":"cannot_self_demote_last_admin"}`
- AND the database state is unchanged
- AND an audit log row exists with `action='self_demote_blocked'` for forensic visibility

### Requirement: Admin Can Deactivate And Reactivate A User With Token Revocation

The `order-service`, on `POST /api/v1/admin/users/{id}/deactivate` from an active admin, MUST set the target `users.active=false` AND MUST mark every `refresh_tokens` row for that user with `revoked=true`. Subsequent `POST /api/v1/auth/refresh` calls presenting tokens from that user MUST return HTTP 401 (covered by canonical `Refresh Token Replay Revokes The Entire Token Family` plus the new bulk-revoke step). An admin MUST NOT be able to deactivate themselves when they are the only active admin; the response MUST be HTTP 409 with `{"error":"cannot_deactivate_last_admin"}`.

Reactivation is the symmetric operation: `POST /api/v1/admin/users/{id}/reactivate` sets `active=true` and creates a `request_password_reset=true` flag for the user (they will be required to change their password at next login).

#### Scenario: Deactivating A Repartidor Revokes Their Tokens

- GIVEN an active admin caller (id 99)
- AND a target repartidor (id 5) with `active=true` and three refresh-token rows, none revoked
- WHEN the admin calls `POST /api/v1/admin/users/5/deactivate`
- THEN the response status is 200
- AND `users` row id 5 has `active=false`
- AND every `refresh_tokens` row with `user_id=5` has `revoked=true`
- AND an audit log row exists with `action='deactivate'`

#### Scenario: Deactivating The Sole Admin Is Blocked

- GIVEN exactly one active admin exists, that admin is the caller (id 99)
- WHEN the admin calls `POST /api/v1/admin/users/99/deactivate`
- THEN the response status is 409
- AND the response body equals `{"error":"cannot_deactivate_last_admin"}`
- AND the database state is unchanged

#### Scenario: Reactivate Sets request_password_reset

- GIVEN a target user (id 5) with `active=false`
- WHEN an active admin calls `POST /api/v1/admin/users/5/reactivate`
- THEN the response status is 200
- AND `users` row id 5 has `active=true` and `must_change_password=true`
- AND an audit log row exists with `action='reactivate'`

### Requirement: Admin Can Emit And Revoke Invite Tokens For New Admins

The `order-service`, on `POST /api/v1/admin/invites` from an active admin, MUST accept a body `{"email": "...", "role": "ADMIN"}` (or `"REPARTIDOR"`), generate a single-use token whose payload includes `email`, `role`, `jti` (random UUID), `exp` (issued_at + 24h), sign the token with HMAC-SHA256 using a key derived from the admin-invite salt (configured alongside `JWT_SECRET`), and return HTTP 201 with body `{"invite_id": "...", "token": "...", "expires_at": "ISO-8601"}`. The cleartext token is returned ONLY at creation time. The `invite_id` and `expires_at` are persisted in `admin_invites` along with the bcrypt-hashed token (not the token itself).

On `POST /api/v1/admin/invites/redeem` (an unauthenticated endpoint), the client supplies `{"token":"...", "password":"..."}`. The service MUST verify the HMAC, verify `exp` is in the future, verify the token is not used, then create a `users` row with the supplied email, the supplied role, `must_change_password=false` (the user just chose a password), `active=true`, and the bcrypt-hashed password. The token is then marked used.

#### Scenario: Creating An Invite Returns Token Once

- GIVEN an active admin caller
- WHEN the admin calls `POST /api/v1/admin/invites` with body `{"email":"new.admin@x.com", "role":"ADMIN"}`
- THEN the response status is 201
- AND the body contains `invite_id`, `token`, `expires_at`
- AND a row exists in `admin_invites` with `email='new.admin@x.com'`, `role='ADMIN'`, `used_at=null`, `expires_at` 24h in the future

#### Scenario: Redeeming An Invite Creates The User

- GIVEN a pending invite for `new.admin@x.com` with role `ADMIN` and a valid token
- WHEN the recipient calls `POST /api/v1/admin/invites/redeem` with `{"token":"...", "password":"correct horse battery staple"}` (≥12 chars to satisfy the password policy)
- THEN the response status is 201
- AND a `users` row exists with `email='new.admin@x.com'`, `roles` containing `["ADMIN"]`, `active=true`, `must_change_password=false`
- AND the `admin_invites` row has `used_at` set to the current timestamp

#### Scenario: Replaying A Used Token Returns 410

- GIVEN an invite that was already redeemed (`used_at` is set)
- WHEN any client calls `POST /api/v1/admin/invites/redeem` with that token
- THEN the response status is 410
- AND the response body equals `{"error":"invite_already_used"}`

#### Scenario: Submitting An Expired Token Returns 410

- GIVEN an invite whose `expires_at` is in the past
- WHEN a client calls `POST /api/v1/admin/invites/redeem` with that token
- THEN the response status is 410
- AND the response body equals `{"error":"invite_expired"}`

### Requirement: All Admin Write Operations Are Recorded In admin_audit_log

Every state-changing endpoint under `/api/v1/admin/**` — invites, redeem-via-invite, roles change, deactivate, reactivate, plus the signup-role-ignored event from the signup-bypass closure — MUST append exactly one row to `admin_audit_log` per request that returns a non-error response. The audit log row MUST include `actor_user_id` (NULL for signup-role-ignored), `action`, `target_user_id` (when applicable), `target_email`, `before_json` (when applicable), `after_json` (when applicable), `request_id`, and `created_at`. The `admin_audit_log` table MUST be configured with `REVOKE UPDATE, DELETE` so that even superusers cannot mutate historical rows without an explicit migration.

The `GET /api/v1/admin/audit-log` endpoint returns paginated audit rows, newest first.

#### Scenario: Listing Audit Log Returns Newest First

- GIVEN three audit rows created in order A, B, C
- WHEN an active admin calls `GET /api/v1/admin/audit-log?page=1&page_size=10`
- THEN the response status is 200
- AND the items array contains C first, then B, then A

#### Scenario: Audit Log Cannot Be Mutated

- GIVEN an `admin_audit_log` table configured with `REVOKE UPDATE, DELETE`
- WHEN anyone (including the database superuser running migrations) attempts to `UPDATE` or `DELETE` a row through the application connection
- THEN the database rejects the statement

#### Scenario: Audit Log Captures The Signup Role-Ignored Event

- GIVEN an anonymous POST to `/api/v1/auth/signup` with `role=ADMIN` in body
- WHEN the signup succeeds
- THEN an `admin_audit_log` row exists with `action='signup_role_ignored'` and `notes='role=ADMIN'`

## Notes

This delta intersects with the canonical auth spec at:

1. `Requirement: Refresh Token Replay Revokes The Entire Token Family` (extended by the bulk deactivation revoke).
2. `Requirement: Vendor Id Claim Is Informational And Never Authoritative` (mirrored for `roles` — the "authority rule for elevated actions" at the top of this delta).
3. The cross-cutting secret-strength invariant is unchanged.

This delta does NOT change refresh-token JWT claim contents outside of the dual-shape `roles`/`role` cut-over window defined in `specs/admin-bootstrap/spec.md`. After cut-over, all tokens MUST carry `roles`.

Ownership by chained PR:

- `admin-roles-signup-closure` owns: Public Signup Ignores Client-Supplied Role And Defaults To CLIENT.
- `admin-roles-gate` owns: Admin Endpoints Are Gated By ADMIN Role In JWT And Active Status In Database (the `RoleAuthorizationFilter` plus `@PreAuthorize` on the new controllers, plus the active-status re-query in the gate).
- `admin-roles-management` owns: List Users; Change Roles; Deactivate/Reactivate; Emit/Revoke Invite Tokens; Audit Log.
- `admin-roles-cutover` (final PR) owns: dual-shape removal — only `roles` claim is valid post-cut-over.
