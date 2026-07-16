# Admin Bootstrap Specification (delta)

> Source-of-truth delta for change `admin-console`. This document is a delta on top of the canonical auth spec at `openspec/specs/auth/spec.md`. It MUST be read together with that spec; anything not redefined here inherits the canonical contract.

## Purpose

This delta defines how the first administrator of the system is created and how the bootstrap path recovers from a "no admins left" lockout. It is intentionally narrow: it does not redefine login, refresh, or token families — those remain fully owned by the canonical auth spec.

The two storage services both continue to refuse to start when `JWT_SECRET` is missing or shorter than 32 bytes; that invariant is unchanged.

### First-admin invariant

The system MUST guarantee that **at least one active user with role `ADMIN` can exist** when the `sync-service` starts against a database that has never been seeded, and MUST guarantee that the only way to *create* the first such user is through the in-process CLI seeder (not through any HTTP endpoint).

A "first admin" is uniquely defined as: a `users` row with `roles` containing `ADMIN` and `active=true`, when the database currently contains zero such rows. Once that row exists, this delta's seeder MUST NOT run again unless explicitly requested with the recovery flag (see REQUIREMENT: `--admin-recover`).

## Requirements

### Requirement: First-Boot Seeder Creates The Single Initial Administrator

The `sync-service`, on a clean boot against a database in which no `users` row has `roles` containing `ADMIN` and `active=true`, MUST create exactly one such row, print the credentials to the process standard output, and continue normal startup. The credentials printed MUST consist of the email address and a cryptographically random one-time password; the password MUST NOT be persisted in plaintext to logs, files, or HTTP responses. The created row MUST have `must_change_password=true`.

The seeder MUST be idempotent across boots: a second boot against a database that already contains at least one active admin MUST NOT create an additional admin, MUST NOT print further credentials, and MUST NOT reset the existing admin's `must_change_password` flag.

The seeder MUST refuse to write a row when the database has any user whose `roles` contains `ADMIN` regardless of `active` value, but SHOULD log a warning that an inactive admin exists if that is the case; recovery in that situation is via REQUIREMENT: `--admin-recover`.

#### Scenario: First Boot Against Empty Database Creates One Admin And Prints Credentials

- GIVEN the database is empty (no `users` rows exist)
- WHEN `sync-service` starts
- THEN the process MUST create exactly one `users` row with `roles` containing `ADMIN`, `active=true`, `must_change_password=true`, a bcrypt-hashed password, and a generated email of the form `admin+<short-uuid>@bootstrap.local`
- AND the process MUST print to standard output a single message containing the generated email and the cleartext password
- AND the process MUST start normally and accept traffic thereafter
- AND the process MUST NOT persist the cleartext password to any log file, response body, or supplementary storage

#### Scenario: Second Boot With At Least One Active Admin Does Not Duplicate

- GIVEN the database has at least one `users` row with `roles` containing `ADMIN` AND `active=true`
- WHEN `sync-service` boots again
- THEN the seeder MUST NOT create any additional `users` row
- AND the seeder MUST NOT print any credentials
- AND the existing admin's `must_change_password` MUST remain whatever value it currently holds

#### Scenario: Seeder Prints To Stdout Not To Logs

- GIVEN the database is empty
- WHEN the seeder runs
- THEN the credentials MUST be emitted to the JVM's standard output stream (`System.out`)
- AND the credentials MUST NOT appear in any file-backed logger output (logback/console-appender writing to a file)
- AND the credentials MUST NOT be reachable via any HTTP endpoint

### Requirement: First Login Of A Bootstrap Admin Forces A Password Change

The `sync-service`, on `POST /api/v1/auth/login` for a user whose `must_change_password=true`, MUST return HTTP 200 with a valid access token and refresh token plus a body field `"must_change_password": true`, and MUST treat subsequent authenticated calls to `/api/v1/auth/change-password` as the only way to clear the flag.

This requirement extends — does not replace — the canonical `Login With Valid Credentials` requirement, which already covers the success path. The new constraint here is the additional flag and the privileged path to clear it.

#### Scenario: Bootstrap Admin Login Returns must_change_password Flag

- GIVEN a `users` row created by the first-boot seeder with `must_change_password=true`
- WHEN the client calls `POST /api/v1/auth/login` with that user's credentials
- THEN the response status is 200
- AND the response body contains `access_token`, `refresh_token`, `expires_in` equal to 900, and `must_change_password: true`

#### Scenario: Calling An Admin Endpoint While must_change_password Is Set Returns 403

- GIVEN an authenticated request whose principal has `must_change_password=true`
- WHEN the client calls any endpoint under `/api/v1/admin/**`
- THEN the response status is 403
- AND the response body equals `{"error":"must_change_password_required"}`
- AND the audit log MUST NOT receive an entry for the failed attempt

#### Scenario: Calling Authenticated Non-Admin Endpoints While must_change_password Is Set Is Allowed

- GIVEN an authenticated request whose principal has `must_change_password=true`
- WHEN the client calls a non-admin authenticated endpoint, for example `GET /api/v1/orders/catalog`
- THEN the response is processed normally (not rejected)

#### Scenario: Successful Password Change Clears The Flag

- GIVEN an authenticated request whose principal has `must_change_password=true`
- AND the request includes a valid `Authorization: Bearer <token>` header
- WHEN the client calls `POST /api/v1/auth/change-password` with a new password meeting the password policy (length, complexity)
- THEN the response status is 200
- AND the user row's `must_change_password` becomes false
- AND the new password hash is the only credential that authenticates the user from now on
- AND every previously issued access token issued while `must_change_password=true` MUST be considered invalidated by the next refresh attempt (their token families remain valid for refresh; the next refresh succeeds but carries `must_change_password: false`)

### Requirement: `--admin-recover` Recreates Admin Role When No Active Admin Exists

The `sync-service`, when started with the JVM flag `--admin-recover=<email>`, MUST attempt to create a `users` row with the supplied email and `roles` containing `ADMIN` if and only if the database has zero rows whose `roles` contains `ADMIN` AND `active=true`. The created row MUST have `must_change_password=true`; the seeder MUST print the new password to stdout.

The `--admin-recover` flag MUST be a no-op when any active admin already exists (a safety check that prevents accidental re-seeding).

This requirement exists so that an operator who has lost every admin (mass deactivation, lockout) can recover without resorting to direct SQL.

#### Scenario: Recover With No Active Admin Creates New Admin With Must Change Password

- GIVEN the database has at least one `users` row, but none of them has both `roles` containing `ADMIN` AND `active=true`
- WHEN `sync-service` is started with `--admin-recover=ceo@hielo.com`
- THEN a `users` row is created with that email, `roles` containing `ADMIN`, `active=true`, `must_change_password=true`, and a fresh random password
- AND the credentials (email + new password) are printed to stdout
- AND the service MUST refuse the boot if the supplied email already exists as an `active` non-admin user, returning a clear error message

#### Scenario: Recover With At Least One Active Admin Is A No-Op

- GIVEN the database has at least one `users` row with `roles` containing `ADMIN` AND `active=true`
- WHEN `sync-service` is started with `--admin-recover=anybody@hielo.com`
- THEN no new `users` row is created
- AND the service starts normally
- AND a structured log line at WARN is emitted noting the recovery attempt was a no-op

### Requirement: Roles Claim Migration Is Backward-Compatible During The Cut-Over Window

This delta extends the canonical `Order-Service Accepts A Valid Bearer Access Token And Populates Security Context` requirement: in addition to the existing `vendor_id` claim, an access token MAY carry a `roles` claim whose value is a JSON array of role strings (e.g. `["ADMIN", "CLIENT"]`). During a cut-over window — defined for this delta as bounded by deployment of every `sync-service` instance on the new JWT issuer — services MUST accept EITHER shape: a single-string `role` claim OR a `roles` array claim.

After the cut-over window closes (decided in `sdd-design`), the canonical claim becomes `roles` only. This delta does not assert that the cut-over window has a fixed length; it requires that the parse behavior is dual-shape for at least one full release cycle of overlap between old and new issuers.

#### Scenario: Token With Roles Array Is Parsed Identically To Single-Role

- GIVEN an access token whose claims include `roles: ["ADMIN", "CLIENT"]`
- WHEN `order-service` parses it
- THEN `AdminContext.hasRole("ADMIN")` returns true
- AND `AdminContext.hasRole("CLIENT")` returns true
- AND the canonical `VendorContext.exposeVendorId()` continues to expose `vendor_id` if present, unchanged

#### Scenario: Legacy Token With role String Is Still Parsed

- GIVEN an access token whose claims include only a single-string `role: "REPARTIDOR"` (no `roles` array)
- WHEN `order-service` parses it
- THEN `AdminContext.hasRole("REPARTIDOR")` returns true
- AND `AdminContext.hasRole("ADMIN")` returns false
- AND the canonical requirements about `vendor_id` being informational only continue to hold

## Notes

This delta intersects with the canonical auth spec exactly at four places:

1. `Requirement: Login With Valid Credentials Returns Access And Refresh Tokens` (extended by the `must_change_password` flag).
2. `Requirement: Order-Service Accepts A Valid Bearer Access Token And Populates Security Context` (extended by the dual-shape `roles` claim).
3. The cross-cutting secret-strength invariant (untouched — both services still refuse to start when `JWT_SECRET` is missing or <32 bytes).
4. The cross-cutting "informational-only" rule for `vendor_id` (untouched — `roles` does not become authoritative just because it's issued with the token; elevated actions in `/api/v1/admin/**` MUST re-query the user row in the database before granting privileges).

This delta does **not** introduce a new "informational only" rule for `roles`: `roles` is authoritative for the gate itself (a token without `ADMIN` cannot call admin endpoints), but specific *elevated* operations still re-query the database to verify the user is still `active` and still carries that role.

Ownership by chained PR:

- `admin-bootstrap-seeder` owns: First-Boot Seeder; First Login Forces Password Change; --admin-recover.
- `admin-roles-cutover` owns: Roles Claim Migration Is Backward-Compatible.
- The privileged `/api/v1/admin/**` endpoints, audit log, and the closure of the signup bypass are owned by `specs/admin-roles/spec.md` (sister delta).
