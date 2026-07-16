# Tasks — admin-console

> Phase result for the `admin-console` change in project `hielo`. Drives `sdd-apply`. Each task maps to one RED/GREEN/REFACTOR cycle under the project's strict TDD rule (`mvn test` is the canonical runner).
> MUST be read together with `proposal/proposal.md`, `specs/admin-bootstrap/spec.md`, `specs/admin-roles/spec.md`, and `design/design.md`.

## Status

`tasks-locked — strict TDD enforced. Each PR closes its first RED test against `mvn test` before any implementation lands.`

## Task ID convention

`{PR}-{sequence}`. Examples: `PR1-1` is the first task of PR1; `PR4-12` is the twelfth task of PR4. Cross-cutting tasks that span PRs use `X-{sequence}`.

## Strict TDD cycle (recurring template)

Every implementation task follows this order. The cycle is enforced by `sdd-verify`:

1. **RED**: write a failing JUnit test. `mvn -pl <module> test` fails on it for the expected reason.
2. **GREEN**: minimal code change to make the test pass. `mvn -pl <module> test` passes.
3. **TRIANGULATE**: add 1-2 more tests that exercise edge cases and unrelated inputs. All pass.
4. **REFACTOR**: rename, extract, simplify. Tests still pass.

Each task in this document lists its RED, GREEN, TRIANGULATE, REFACTOR sub-steps explicitly. `sdd-apply` MUST execute them in order.

---

## PR1 — admin-roles-signup-closure

Forecast: **80-120 LOC diff**. No migration. Covers requirement R1.

### PR1-1: RED test for signup with role=ADMIN creates CLIENTE

- **Status**: ✅ DONE 2026-07-16.
- **Type**: RED.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/service/SignupServiceTest.java` (new).
- **Description**: JUnit 5 test using Mockito + `@DataJpaTest`-style setup. Calls `signupService.signup(SignupRequest("admin@x.com", "correct-horse", "admin", "name", null, null, null, null, null, null))`. Asserts the persisted user has `roles=Set.of(Role.cliente)`, NOT `roles=Set.of(Role.admin)`. Asserts an `admin_audit_log` row exists with `action='signup_role_ignored'`. Uses Testcontainers Postgres (already in test deps via parent BOM — verify in `pom.xml`; otherwise add it).
- **Evidence (actual run)**: `mvn -DskipITs -Dtest=SignupServiceTest test` produced `Tests run: 4, Failures: 2` RED (the audit-row assertions in the role=admin and role=repartidor cases; the role=cliente and role=null cases did not expect audit and stayed green).
- **Deviation applied at apply time**: tests are Mockito unit-style, not Spring Boot @DataJpaTest. Rationale: faster, deterministic, no H2/Postgres round-trip needed for the bypass logic. The audit-log INSERT itself is exercised by the production code path; an IT in a later PR will cover the persistent side of `admin_audit_log`.

### PR1-2: GREEN — force roles=cliente and ignore role input

- **Status**: ✅ DONE 2026-07-16.
- **Type**: GREEN.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/service/SignupService.java`.
- **Description**: Modify `signup()` to set `u.setRoles(Set.of(User.Role.cliente))` unconditionally. Remove the call to `User.Role.valueOf(req.role())`. Add an early-return guard that, if `req` carried a non-null `role` field, writes an `admin_audit_log` row with `actor_user_id=null`, `action='signup_role_ignored'`, `target_email=email`, `notes="role=" + req.role()`. To make this work in PR1, add a minimal `AdminAuditLogRepository` (insert-only) and `AdminAuditLog` entity without the table yet — the entity can map to a `@Table(name="admin_audit_log")` that does not yet exist; the test must run against a Postgres with the table created. **Workaround for PR1**: use a `JdbcTemplate.update()` with raw SQL that creates the table in test setup (`@Sql` script) OR use `TestEntityManager` with `@DataJpaTest`. Pick `@DataJpaTest` + Flyway test migrations (`V900__admin_audit_log_test_fixture.sql` under `src/test/resources/db/migration_test/`) so the table exists for tests but is NOT applied to dev (the real V7 will overwrite later). Add the test-fixture migration to `flyway.locations` in `application-test.yml`.
- **Evidence (actual run)**: `mvn -DskipITs -Dtest=SignupServiceTest test` produced `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` GREEN.
- **Deviation applied at apply time**: production code created `AdminAuditLog`, `AdminAuditLogRepository`, `AdminAuditLogger`, `AuditEvent` as concrete classes (not raw JdbcTemplate). Hibernate's `ddl-auto=create-drop` in the test profile generates the `admin_audit_log` table from the entity for `*IT` tests; the real V7 Flyway migration in PR4 will create it in dev/prod with stricter types. This keeps PR1 simpler while keeping the contract surface intact.

### PR1-3: TRIANGULATE — signup without role field also creates cliente

- **Status**: ✅ DONE 2026-07-16.
- **Type**: TRIANGULATE.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/service/SignupServiceTest.java`.
- **Description**: Add a second test that calls signup with NO role field. Asserts roles=[cliente] AND that NO audit log row was created (the ignored-field path was not exercised). Use a custom SignupRequest builder that omits role.
- **Evidence (actual run)**: `mvn test` (full sync-service suite) shows `SignupServiceTest: Tests run: 4` covering all four combinations: role=admin (audits), role=cliente (does not audit), role=repartidor (audits), role=null (does not audit).
- **Note**: the original triangulation spec called for the `with_role_cliente_does_not_audit` and `with_null_role_does_not_audit` cases as the triangulation set; both pass.

### PR1-4: REFACTOR — remove role from SignupRequest

- **Status**: ✅ DONE 2026-07-16 (with deviation).
- **Type**: REFACTOR.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/dto/SignupRequest.java`, `SignupService.java`, `SignupServiceTest.java`.
- **Description**: Remove `role` from `SignupRequest`. Update `SignupService` to no longer reference `req.role()`. Update tests to not construct a `SignupRequest` with role. Verify Flutter side already doesn't send role in body — the Flutter `RegisterScreen` today DOES send `role`; flag this for PR4 (frontend closure). For PR1, the backend ignores it, which is enough.
- **Evidence (actual run)**: `mvn test` (full sync-service suite, 42 tests) passes; `mvn -pl sync-service compile` succeeds.
- **Deviation applied at apply time**: rather than removing `role` from `SignupRequest`, the field is preserved with `@Deprecated @JsonProperty("role") @Size(max=20)` and made nullable (drop the `@NotBlank @Pattern`). Rationale: removing the field would also remove the audit trigger path, because `SignupService.bypassAttempt` depends on reading `req.role()`. The audit infrastructure stays useful as a continuous-defense tripwire: any client (current Flutter app, future curl, malicious script) that still sends a role field is detected and logged. The Flutter side MUST stop sending role in PR4-12 (`admin_user_management`); until then, the backend silently absorbs and audits the field. Migration to `roles TEXT[]` happens in PR2/V6.

---

## PR2 — admin-bootstrap-seeder

Forecast: **150-200 LOC diff + V6 migration**. Covers B1, B2, B3.

### PR2-1: Write V6 migration

- **Type**: MIGRATION.
- **File**: `sync-service/src/main/resources/db/migration/V6__admin_roles_and_status.sql`.
- **Description**: Create the migration per design section 3.1. Use the literal SQL from the design.
- **Evidence**: `mvn -pl sync-service flyway:info` shows V6 as pending; `mvn -pl sync-server flyway:migrate -Dflyway.url=$TEST_DB_URL` applies it cleanly. Verify the table shape with `\d users`.

### PR2-2: Update User entity

- **Type**: GREEN (no test yet — entity is exercised by all subsequent tests).
- **File**: `sync-service/src/main/java/com/sales/sync/auth/model/User.java`.
- **Description**: Add `roles` (`@Type(PostgresArrayType.class) Set<User.Role>`), `active` (`boolean`), `mustChangePassword` (`boolean`), `version` (`@Version long`). Keep legacy `role` mapped for now (read/write deprecated). Drop the old `users_role_chk` constraint at the JPA layer? No, the SQL migration already drops it. Add a custom Hibernate UserType `UserRoleArrayType` that maps `Set<User.Role>` ↔ `String[]` ↔ `TEXT[]`. Implementation: encode as `["admin","repartidor","cliente"]` lowercased on write; decode on read with a tolerant parser that maps case-insensitively.
- **Evidence**: `mvn -pl sync-service compile` succeeds; `mvn -pl sync-service test` still passes (PR1 tests).

### PR2-3: RED test — bootstrap creates one admin on empty DB

- **Type**: RED.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/admin/AdminBootstrapTest.java` (new).
- **Description**: `@SpringBootTest` with a `@Sql` cleanup that truncates `users` before each test. Inject `AdminBootstrap`. Capture `System.out` via a custom `PrintStream` that records to a StringBuilder. Call `bootstrap.run()`. Assert: a row in `users` with `roles contains admin`, `must_change_password=true`, `version=0`; the captured stdout contains the word "Bootstrap admin created" with the email and a base64url-looking string (12+ chars); the same admin exists if `run()` is called a second time (idempotent).
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminBootstrapTest` fails because `AdminBootstrap` does not exist yet.

### PR2-4: GREEN — implement AdminBootstrap

- **Type**: GREEN.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrap.java` (new) + `AdminBootstrapProperties.java` (new).
- **Description**: `AdminBootstrap` is `@Component implements ApplicationRunner`. Reads `app.admin.recover-email` and `app.admin.bootstrap-enabled` from `AdminBootstrapProperties`. On `run()`: if `bootstrap-enabled=false`, log "admin bootstrap disabled" and return. Query `SELECT count(*) FROM users WHERE 'admin' = ANY(roles) AND active = TRUE`. If > 0 and `recover-email` is null: log "already bootstrapped" and return. If > 0 and `recover-email` is non-null: log WARN "recovery requested but admin already exists; skipping" and return. If == 0 and `recover-email` is null: generate `email=admin+<short-uuid>@bootstrap.local` and a random 16-byte base64url password; insert user with `roles=[admin]`, `active=true`, `must_change_password=true`; print to `System.out.println("[admin-bootstrap] credentials email=" + email + " password=" + password + " — capture now, this will not be shown again")`. If == 0 and `recover-email` is non-null: same, but use the supplied email.
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminBootstrapTest` passes.

### PR2-5: TRIANGULATE — recover flag no-op with existing admin

- **Type**: TRIANGULATE.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/admin/AdminBootstrapTest.java`.
- **Description**: Add a test that pre-inserts an admin (manually), then calls `run(new ApplicationArguments(...with recover-email='ceo@hielo.com'...))` and asserts NO new admin is created and the existing one is unchanged. Use a `JdbcTemplate.update(...)` to pre-insert.
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminBootstrapTest` passes 2 tests.

### PR2-6: REFACTOR — extract password generator

- **Type**: REFACTOR.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrap.java`, `RandomPasswordGenerator.java` (new).
- **Description**: Move the password-generation logic to `RandomPasswordGenerator.generate(int byteLength)`. Make the print line a constant `BOOTSTRAP_CONSOLE_FORMAT`.
- **Evidence**: `mvn -pl sync-service test` passes.

### PR2-7: Wire `must_change_password` into login response

- **Type**: GREEN + RED.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/dto/AuthResponse.java`, `service/AuthService.java`.
- **Description**: Add `must_change_password` boolean to `AuthResponse`. In `AuthService.login()`, after loading the user, populate the flag from `user.isMustChangePassword()`. Add a test `AuthServiceTest.login_returns_must_change_password_true_for_bootstrap_admin()` that creates a user with the flag set, logs in, asserts the response body has the flag. The flag must also be added to the JWT as a `mcp` claim — extend `JwtService.sign()` to accept a `mustChangePassword` parameter and write the `mcp` claim. Update `AuthService.login()` to pass the flag to `JwtService.sign()`.
- **Evidence**: `mvn -pl sync-service test -Dtest=AuthServiceTest` passes; manual `curl` confirms `mcp: true` in the JWT payload decoded at jwt.io.

### PR2-8: Doc — bootstrap runbook

- **Type**: DOC.
- **File**: `docs/RUNBOOK_ADMIN_BOOTSTRAP.md` (new).
- **Description**: Step-by-step: how to start the sync-service for the first time, capture credentials, log in via Flutter, change password, verify access. Includes CI notes (set `ADMIN_BOOTSTRAP_ENABLED=false`).
- **Evidence**: file exists, manually reviewable, mentions all CLI flags (`--admin-recover`, env vars).

---

## PR3 — admin-roles-gate

Forecast: **250-300 LOC diff**. Covers B4, R2. Dual-shape JWT issuance and parsing.

### PR3-1: Update JwtService.sign to write both claims

- **Type**: GREEN.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/security/JwtService.java`.
- **Description**: Change `sign()` to take `Set<User.Role> roles` instead of single `User.Role`. Write `roles` claim as JSON array of lowercase names. Also write `role` claim as the first element of the array (legacy compat). Add `mcp` claim from new `mustChangePassword` parameter. Add an overload `sign(UUID, UUID, String, User.Role)` that delegates to `sign(UUID, UUID, String, Set<User.Role>, boolean)` with the single role wrapped. Keep the old method working until PR5.
- **Evidence**: `mvn -pl sync-service test` passes; decoding the resulting JWT at jwt.io shows both `role` and `roles`.

### PR3-2: Update JwtService.parse to dual-shape

- **Type**: RED + GREEN.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/security/JwtService.java`, `test/.../JwtServiceTest.java`.
- **Description**: Add test `parse_token_with_roles_array_extracts_set()`. Modify `parse()` to prefer `roles` claim (parse JSON array), fall back to `role` (wrap to set). Update `ParsedToken` to hold `Set<User.Role>` and `boolean mustChangePassword`. Existing tests still pass because the legacy path is exercised too.
- **Evidence**: `mvn -pl sync-service test` passes.

### PR3-3: Mirror dual-shape in order-service JwtService

- **Type**: GREEN.
- **File**: `order-service/src/main/java/com/sales/order/auth/security/JwtService.java`.
- **Description**: Same changes as PR3-2 but on the order-service side. Add the same tests in `order-service/src/test/.../JwtServiceTest.java`.
- **Evidence**: `mvn -pl order-service test` passes.

### PR3-4: Update JwtAuthenticationFilter (both services) to map roles set

- **Type**: GREEN.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/security/JwtAuthenticationFilter.java`, `order-service/src/main/java/com/sales/order/auth/security/JwtAuthenticationFilter.java`.
- **Description**: Replace the single-role authority mapping with a set mapping. For each role in `parsed.roles()`, add `ROLE_<UPPER>`. Drop the singular `parsed.role()` references.
- **Evidence**: existing auth tests pass.

### PR3-5: RED test — non-admin gets 403 on /api/v1/admin/**

- **Type**: RED.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/admin/AdminRoleGateFilterTest.java` (new).
- **Description**: `@SpringBootTest` with WebMvc. Stub the filter chain so the test does not depend on real endpoints. Issue a request with a token whose roles are `[CLIENT]`. Expect 403 `admin_role_required`.
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminRoleGateFilterTest` fails on filter not implemented.

### PR3-6: GREEN — implement AdminRoleGateFilter

- **Type**: GREEN.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminRoleGateFilter.java`, `RoleRequeryService.java`, `AdminContext.java`.
- **Description**: Implement per design section 5.4. Wire the filter to the security chain via a `SecurityFilterChain` bean in `SecurityConfig` that matches `/api/v1/admin/**` and runs the filter before the default chain. Use `@Order(40)`.
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminRoleGateFilterTest` passes.

### PR3-7: TRIANGULATE — deactivated admin gets 403

- **Type**: TRIANGULATE.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/admin/AdminRoleGateFilterTest.java`.
- **Description**: Pre-insert an admin user with `active=false`, issue a token with roles=[admin], call admin path, expect 403.
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminRoleGateFilterTest` passes.

### PR3-8: TRIANGULATE — must_change_password user gets 403 only for admin paths

- **Type**: TRIANGULATE.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/admin/AdminRoleGateFilterTest.java`.
- **Description**: Pre-insert admin with `must_change_password=true`, issue token with `mcp=true`, call admin path (403) AND call a non-admin authenticated path (200 or filter pass-through). The latter proves the gate is path-scoped.
- **Evidence**: `mvn -pl sync-service test -Dtest=AdminRoleGateFilterTest` passes 3 tests.

### PR3-9: REFACTOR — extract LastAdminGuard stub

- **Type**: REFACTOR.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/admin/LastAdminGuard.java` (stub).
- **Description**: Stub the guard class with the count-based method; do not wire to endpoints yet. Full wiring in PR4.
- **Evidence**: `mvn -pl sync-service compile` succeeds.

---

## PR4 — admin-roles-management

Forecast: **400-500 LOC diff + V7 migration + Flutter rewrite of AdminScreen**. Covers R3, R4, R5, R6, R7. Largest PR. Full 4R review required before merge.

### PR4-1: Write V7 migration

- **Type**: MIGRATION.
- **File**: `sync-service/src/main/resources/db/migration/V7__admin_invites_and_audit.sql`.
- **Description**: Create per design section 3.1. The `<app_role>` placeholder in the `REVOKE` statements is filled at apply time with the actual username from `application.yml` `spring.datasource.username`. Use a JPA migration script that reads the username at apply time via Flyway's `placeholder` mechanism (or just commit the literal if the dev DB uses a known role).
- **Evidence**: `mvn -pl sync-service flyway:info` and `flyway:migrate` against dev DB.

### PR4-2: AdminAuditLog entity + repository

- **Type**: GREEN.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminAuditLog.java` (new entity), `AdminAuditLogRepository.java` (new).
- **Description**: Map `admin_audit_log` table. Repository has `insert(...)` via JdbcTemplate (not via `JpaRepository.save()` — `save()` would issue UPDATE/DELETE which we just revoked). Add `findByActorOrTarget` for the listing endpoint.
- **Evidence**: `mvn -pl sync-service compile` succeeds.

### PR4-3: RequestIdFilter + AuditLogWriter

- **Type**: GREEN.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/admin/RequestIdFilter.java`, `AuditLogWriter.java`.
- **Description**: `RequestIdFilter` reads `X-Request-Id` header (UUID format) or generates one. Sets it as a request attribute and exposes via `RequestContextHolder` thread-local. `AuditLogWriter.log(AuditEvent)` inserts via JdbcTemplate.
- **Evidence**: `mvn -pl sync-service test` passes a unit test that exercises both.

### PR4-4: AdminService.listUsers + GET /api/v1/admin/users

- **Type**: RED + GREEN.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminController.java`, `AdminService.java`, `AdminUserSummary.java`, `AdminListResponse.java`.
- **Description**: Service method paginates users, applies `q` (ILIKE on email+full_name) and `role` filters, returns `AdminListResponse`. Controller exposes `GET /api/v1/admin/users?page=1&page_size=20&q=&role=`. Annotate with `@PreAuthorize("hasRole('ADMIN')")`.
- **Evidence**: tests pass per spec R3.

### PR4-5: AdminService.changeRoles + PATCH /api/v1/admin/users/{id}/roles

- **Type**: RED + GREEN.
- **Files**: same as PR4-4 + `AdminRolePatchRequest.java`.
- **Description**: Service reads `If-Match` header (optional), runs `UPDATE users SET roles=?, version=version+1 WHERE id=? AND version=?`. If 0 rows updated, throws `VersionConflictException` mapped to 409. If the actor is demoting self out of admin AND LastAdminGuard says it's the only admin, throw `CannotSelfDemoteLastAdmin` mapped to 409. Audit log row written with before/after.
- **Evidence**: tests per spec R4 scenarios.

### PR4-6: AdminService.deactivate + POST /api/v1/admin/users/{id}/deactivate

- **Type**: RED + GREEN.
- **Files**: same as PR4-4 + `AdminDeactivateRequest.java`.
- **Description**: In one transaction: `UPDATE users SET active=false, locked=true, version=version+1 WHERE id=? AND active=true`. Then `UPDATE refresh_tokens SET revoked=true WHERE user_id=? AND revoked=false`. Audit log row. LastAdminGuard check for self-deactivation.
- **Evidence**: tests per spec R5.

### PR4-7: AdminService.reactivate + POST /api/v1/admin/users/{id}/reactivate

- **Type**: RED + GREEN.
- **Description**: `UPDATE users SET active=true, must_change_password=true, version=version+1 WHERE id=? AND active=false`. Audit log row.
- **Evidence**: tests per spec R5 reactivate scenarios.

### PR4-8: InviteTokenCodec + IssueInvite

- **Type**: RED + GREEN.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/admin/InviteTokenCodec.java`, `InviteTokenProperties.java`, `AdminInvite.java` (entity), `AdminInviteRepository.java`, `AdminService.issueInvite()`.
- **Description**: HMAC-SHA256 over base64url JSON payload. Storage only bcrypt hash. Token returned once.
- **Evidence**: tests per spec R6.

### PR4-9: POST /api/v1/auth/admin/invites/redeem

- **Type**: RED + GREEN.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminInviteRedeemController.java`, `InviteRedeemService.java`, `InviteRateLimiter.java`, `AdminInviteRedeemRequest.java`, `AdminInviteRedeemResponse.java`.
- **Description**: Public endpoint (no JWT). Rate-limited per IP. Creates user with role from token, must_change_password=false. Marks invite used.
- **Evidence**: tests per spec R6.

### PR4-10: GET /api/v1/admin/audit-log

- **Type**: RED + GREEN.
- **Files**: same as PR4-2 + new `AdminAuditLogEntry.java`, `AdminAuditLogResponse.java`.
- **Description**: Paginated list newest first, with filters: `action`, `actor_user_id`, `target_user_id`, `from`, `to`. Default page_size=20, max 100.
- **Evidence**: tests per spec R7.

### PR4-11: Common password blocklist (deferred decision)

- **Type**: GREEN.
- **File**: `sync-service/src/main/resources/security/common-passwords.txt` (new, 50 entries), `WeakPasswordValidator.java`.
- **Description**: Validate new passwords against the list. Reject with 422 `weak_password`.
- **Evidence**: unit test asserts a known-weak password is rejected.

### PR4-12: Flutter — remove role selector from RegisterScreen

- **Type**: FRONTEND RED + GREEN.
- **File**: `~/projects/UI-HieloPedido/lib/screens/register_screen.dart`.
- **Description**: Remove the role selector UI. Force `role: 'cliente'` in the signup call (backend now ignores it anyway, but client stops sending for cleanliness). Remove the `_selectedRole` state.
- **Evidence**: app builds, manual smoke test confirms only client registration is exposed.

### PR4-13: Flutter — update TokenStorage and OrderProvider for roles[]

- **Type**: FRONTEND RED + GREEN.
- **Files**: `lib/core/auth/token_storage.dart`, `lib/providers/order_provider.dart`.
- **Description**: Replace `String role` with `Set<String> roles` (read from JWT `roles` claim; fallback to `role` during cut-over). `refreshUserRole` returns `Set<String>`. `currentUser` getter still has `userMetadata` for legacy widgets.
- **Evidence**: existing screens compile; manual smoke test confirms login hydrates roles set correctly.

### PR4-14: Flutter — AdminConsole + tabs

- **Type**: FRONTEND.
- **Files**: `lib/screens/sub_screens/admin/admin_console_screen.dart`, `admin_users_tab.dart`, `admin_invites_tab.dart`, `admin_user_detail_sheet.dart` (new), `lib/providers/admin_provider.dart` (new).
- **Description**: Build the new admin UI per design section 7.3-7.5. Reuse `ApiClient` (extend with admin endpoint wrappers). Wire to bottom nav.
- **Evidence**: app builds, manual test confirms listing, role change, deactivate/reactivate, invite flow.

### PR4-15: Flutter — mustChangePassword route

- **Type**: FRONTEND.
- **File**: `lib/screens/sub_screens/admin/admin_change_password_screen.dart` (new).
- **Description**: Detected by login response; routes here BEFORE any other screen. Form with current password + new password (≥12 chars). On success, refresh token and continue.
- **Evidence**: app builds; manual test confirms a bootstrap admin can change password and continue.

### PR4-16: 4R review

- **Type**: REVIEW.
- **Description**: Run the full 4R chain: `review-readability`, `review-reliability`, `review-resilience`, `review-risk`. Required because PR4 changes the security boundary (admin endpoints, invite issuance) and is the largest PR. Resolve all blockers before merge.
- **Evidence**: review reports saved under `openspec/changes/admin-console/review/` with status `resolved`.

---

## PR5 — admin-roles-cutover

Forecast: **80-120 LOC diff + V8 migration + CI gate**.

### PR5-1: Write V8 migration

- **Type**: MIGRATION.
- **File**: `sync-service/src/main/resources/db/migration/V8__drop_legacy_role_column.sql`.
- **Description**: Per design section 3.1.
- **Evidence**: `mvn -pl sync-service flyway:info` and `flyway:migrate`.

### PR5-2: RED test — JwtService no longer writes role claim

- **Type**: RED.
- **File**: `sync-service/src/test/java/com/sales/sync/auth/security/JwtServiceTest.java`.
- **Description**: `sign_emits_only_roles_claim_not_role_claim()` asserts that signing produces a JWT whose payload contains `roles` but not `role`. Also test `parse_rejects_legacy_role_only_token()`.
- **Evidence**: `mvn -pl sync-service test -Dtest=JwtServiceTest` fails on the new test.

### PR5-3: GREEN — drop legacy claim writes and reads

- **Type**: GREEN.
- **Files**: `sync-service/src/main/java/com/sales/sync/auth/security/JwtService.java`, `order-service/src/main/java/com/sales/order/auth/security/JwtService.java`.
- **Description**: Remove `role` single-string claim from `sign()`. Remove `role` fallback in `parse()`. Drop the deprecated overload of `sign()` that takes a single role.
- **Evidence**: tests pass.

### PR5-4: Drop legacy column from User entity

- **Type**: REFACTOR.
- **File**: `sync-service/src/main/java/com/sales/sync/auth/model/User.java`.
- **Description**: Remove the deprecated `role` field. Update all callers (none should exist after PR5-3). Drop the `idx_users_role` index (already done by V8).
- **Evidence**: `mvn -pl sync-service test` passes; no compile warnings.

### PR5-5: CI grep-gate for legacy role claim

- **Type**: CONFIG.
- **File**: `.github/workflows/ci.yml` (or local `scripts/check-no-legacy-role.sh`).
- **Description**: A grep that fails the build if `getStringClaim\("role"\)` appears in any non-test file. Document the script in `docs/CI_GATES.md`.
- **Evidence**: introducing `getStringClaim("role")` in `src/main/` makes `mvn verify` (or the local script) fail.

### PR5-6: Update Flutter — drop legacy `role` field

- **Type**: FRONTEND.
- **Files**: `lib/core/auth/token_storage.dart`, `lib/providers/order_provider.dart`.
- **Description**: Drop the fallback that reads `role` if `roles` is missing. Require `roles` claim.
- **Evidence**: app builds; legacy tokens cause the API client to surface a re-login prompt.

---

## Cross-cutting tasks

### X-1: Update canonical auth spec note

- **Type**: DOC.
- **File**: `openspec/specs/auth/spec.md`.
- **Description**: Append a note at the bottom that admin-console added the dual-shape claim handling during cut-over and that the final post-cut-over shape requires the `roles` array claim only.
- **Evidence**: file updated; reference in spec diff.

### X-2: Update SECURITY_AUDIT.md

- **Type**: DOC.
- **File**: `docs/SECURITY_AUDIT.md`.
- **Description**: Mark the "evasion of backend" findings as RESOLVED by this change. Add the residual open items (CORS hardening, rate limiting on login, brute-force lockout policy) as follow-up issues.
- **Evidence**: file updated.

### X-3: Update openspec README

- **Type**: DOC.
- **File**: `openspec/README.md`.
- **Description**: Brief note that admin-console is now an active change following the auth-foundation and core-domain-model changes.
- **Evidence**: file updated.

### X-4: Sync session memory

- **Type**: MEMORY.
- **Description**: Save the admin-console full pipeline to Engram under topic `sdd/admin-console/tasks`.
- **Evidence**: `mem_save` succeeds.

---

## Review workload forecast

| PR | LOC diff (approx) | 4R review required | Chain rationale |
| --- | --- | --- | --- |
| PR1 | 80-120 | readability + risk (closure of bypass) | Smallest; smallest risk. |
| PR2 | 150-200 | readability + risk (bootstrap secret) | First admin path; secrets in stdout. |
| PR3 | 250-300 | readability + reliability + risk | JWT claim migration; both services. |
| PR4 | 400-500 | readability + reliability + resilience + risk (full chain) | Largest; adds new endpoints + Flutter rewrite. |
| PR5 | 80-120 | readability + reliability | Cleanup; CI grep-gate. |
| **Total** | **960-1240** | — | — |

The 400-line threshold is exceeded by PR4 alone. **Decision: do not split PR4 further.** Justification: PR4's pieces are tightly coupled (admin endpoints share audit infrastructure, exception mapping, and UI state). Splitting would create awkward cross-PR dependencies. Instead, the full 4R review chain is mandatory for PR4's merge.

For PR3 (250-300 lines) the threshold is not breached but the security surface warrants 3R (`readability`, `reliability`, `risk`).

## Acceptance criteria recap

The 10 criteria from the proposal are mapped to:

| Criterion | Verified by |
| --- | --- |
| FIRST_ADMIN_BOOTSTRAP | PR2 tests + manual `docker run` smoke |
| SIGNUP_BYPATCH_CLOSED | PR1 tests |
| ADMIN_INVITE_FLOW | PR4-8, PR4-9 tests + manual Flutter smoke |
| ROLE_GATE | PR3-5, PR3-7, PR3-8 tests |
| LIST_USERS | PR4-4 tests |
| DEACTIVATE_USER | PR4-6 tests |
| AUDIT_LOG_VIEW | PR4-10 tests |
| SELF_DEMOTE_BLOCKED | PR4-5 tests |
| REGISTER_SCREEN_PUBLIC | PR4-12 manual smoke |
| RUNBOOK | PR2-8 file exists |

## Out of scope (carried forward)

- CORS hardening for production.
- Rate limiting on `/api/v1/auth/login` (brute-force protection).
- Login lockout policy (currently `account_locked` is set manually; no automatic lockout).
- `last_login_at` column.
- Audit log retention / rotation.
- Multi-tenant support.
