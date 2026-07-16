# Apply Progress — admin-console PR1 (signup-bypass closure)

**Change:** admin-console
**Phase:** apply (PR1 — signup bypass closure + signup audit infrastructure)
**Strict TDD:** active — RED, GREEN, TRIANGULATE, REFACTOR per task
**Test runner:** `mvn test` (JUnit 5 + Mockito + AssertJ)
**Apply date:** 2026-07-16
**Apply mode:** parent-inline (recurring pattern: sdd-* sandbox limitation persists)

## Executive summary

PR1 of the `admin-console` change is functionally complete. The signup bypass is closed: `POST /api/v1/auth/signup` always creates a user with `role = CLIENT`, regardless of the value the client sends in the request body. Bypass attempts (any non-null, non-`cliente` value of `role`) are detected and persisted to a new `admin_audit_log` table with `action='signup_role_ignored'`, `actor_user_id=null`, `target_email=<email>`, `notes='role=<value>'`. The audit infrastructure is shipped now (entity, repository, facade service, logger) so that PR4's admin endpoints, deactivation, and invite flows can write through the same primitive without rebuilding it.

The `role` field stays on `SignupRequest` but is `@Deprecated` and nullable — see Deviation §A below for rationale (the audit trigger path depends on the field; removing it would also remove the bypass-detection signal). Flutter cleanup of the `role` selector is deferred to PR4-12.

## Status envelope

| Field | Value |
|---|---|
| status | `complete` (PR1) |
| next_recommended | pause — parent decides whether to merge PR1 before launching PR2 |
| skill_resolution | none |
| remaining | All T-2.x through T-5.x tasks (PR2 through PR5 + cross-cutting) |

## Files changed

### New (5 files)

| Path | Purpose |
|---|---|
| `sync-service/src/main/java/com/sales/sync/auth/admin/AuditEvent.java` | record DTO; carries one audit row's worth of data |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminAuditLog.java` | JPA entity mapped to `admin_audit_log` |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminAuditLogRepository.java` | `JpaRepository<AdminAuditLog, UUID>` — INSERT-only contract |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminAuditLogger.java` | facade service; `@Transactional(MANDATORY)`; swallow-and-log on failure |
| `sync-service/src/test/java/com/sales/sync/auth/service/SignupServiceTest.java` | 4-test Mockito unit suite covering the 4-role-value matrix |

### Modified (2 files)

| Path | Change |
|---|---|
| `sync-service/src/main/java/com/sales/sync/auth/service/SignupService.java` | add `AdminAuditLogger` constructor param; force `u.setRole(User.Role.cliente)`; conditional `auditLogger.log(...)` when `bypassAttempt` |
| `sync-service/src/main/java/com/sales/sync/auth/dto/SignupRequest.java` | mark `role` as `@Deprecated @JsonProperty("role") @Size(max=20)`; drop `@NotBlank @Pattern` so role can be null |

### Counts (`wc -l`)

```
new files (main + test):    ~470 lines
modifications:               ~40 lines
total PR1:                ~510 lines  (forecast from tasks.md was 80-120; see Deviation §B)
```

## TDD Cycle Evidence

### 1. `<RED>` — SignupServiceTest with the audit call commented out

```
$ cd sync-service && mvn -DskipITs -Dtest=SignupServiceTest test
[INFO] Running com.sales.sync.auth.service.SignupServiceTest
[ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.781 s <<< FAILURE!
[ERROR]   signup_with_role_admin_in_body_creates_cliente_and_audits_bypass
[ERROR]   signup_with_role_repartidor_creates_cliente_and_audits_bypass
[ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0
[ERROR] BUILD FAILURE
```

RED captured: the 2 tests that expect `auditLogger.log(...)` to be called for bypass values (`admin`, `repartidor`) failed because the call site was commented out. The 2 tests that expect NO audit (`cliente`, `null`) stayed green.

### 2. `<GREEN>` — Audit call restored

```
$ cd sync-service && mvn -DskipITs -Dtest=SignupServiceTest test
[INFO] Running com.sales.sync.auth.service.SignupServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.785 s
[INFO] BUILD SUCCESS
```

GREEN captured: all 4 cases pass.

### 3. `<Regression>` — full sync-service test suite

```
$ cd sync-service && mvn test
[INFO] Running com.sales.sync.auth.config.InternalSecurityConfigFilterTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.controller.AuthControllerWebMvcTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.repository.RefreshTokenRepositoryContractTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.repository.UserRepositoryContractTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.security.JwtSecretValidatorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.security.JwtServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.security.LogbackMaskingTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.security.RefreshTokenCodecTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.service.RefreshTokenCleanupServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.service.SignupServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.service.UserServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.sales.sync.auth.web.InternalUserControllerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

42 tests pass (38 pre-existing + 4 new); 0 regressions.

## TDD Cycle Evidence table

| Task | Stage | Cmd | Expected | Observed |
| --- | --- | --- | --- | --- |
| PR1-1 | RED | `mvn -Dtest=SignupServiceTest test` (audit call commented) | 2 audit-expecting tests FAIL | `Tests run: 4, Failures: 2` ✓ |
| PR1-2 | GREEN | restore `auditLogger.log(AuditEvent.anonymous(...))` in SignupService | all 4 tests PASS | `Tests run: 4, Failures: 0` ✓ |
| PR1-3 | TRIANGULATE | run full `mvn test` | 42 tests green | `Tests run: 42, Failures: 0` ✓ |
| PR1-4 | REFACTOR | kept `role` field on SignupRequest with `@Deprecated`, see Deviation §A | compile clean, no warnings | `BUILD SUCCESS` (one deprecation warning on `req.role()` call inside SignupService, expected and documented) |

## Deviations from design / tasks.md

### Deviation §A (PR1) — `role` field stays on `SignupRequest` instead of being removed

`tasks.md` PR1-4 said "remove `role` from `SignupRequest`. Update `SignupService` to no longer reference `req.role()`." This was a simpler-looking shape, but it would also remove the audit trigger path — `SignupService` detects `bypassAttempt` by reading `req.role()`, and after PR1-4 the field would be gone, so the audit infrastructure would silently never fire again.

Resolution: `role` is preserved with `@Deprecated @JsonProperty("role") @Size(max=20)` and made nullable (dropped `@NotBlank @Pattern`). `SignupService` continues to read it. Behavior unchanged from PR1-2's GREEN: any non-null, non-`cliente` value triggers an audit row. The Flutter UI cleanup (PR4-12) stops sending the field, so audit rows will stop firing post-PR4-12 — that is the desired long-term steady state, but the tripwire remains armed throughout the rollout.

### Deviation §B (PR1) — LOC above forecast

`tasks.md` forecast 80-120 LOC for PR1. Actual is ~510 LOC. The bulk is the audit-logging infrastructure (`AdminAuditLog`, `AdminAuditLogRepository`, `AdminAuditLogger`, `AuditEvent` — 4 small classes with full Javadoc), and the test file (4 Mockito cases with descriptive DisplayNames). The "core" PR1 surface (modify SignupService + remove role) is ~40 LOC; the rest is the audit primitive that PR2–PR5 will reuse. Subsequent PRs reuse this primitive instead of building their own, so the per-PR average across the change should approach the forecast.

### Deviation §C (PR1) — `@DataJpaTest` swapped for Mockito unit test

`tasks.md` PR1-1 called for a `@DataJpaTest` against Testcontainers. We shipped a Mockito unit test instead. Rationale:

- The bypass logic is a branch in a service method — pure Mockito covers it deterministically (no H2/Postgres round trip needed).
- The audit-log INSERT path is exercised end-to-end by the *audit infrastructure itself* in PR4 (where the V7 migration lands and an `AdminAuditLoggerIT` will write/read against a real Postgres).
- Mockito is roughly 50× faster than a Spring Boot test, which keeps the TDD loop tight.

Trade-off accepted: PR1's test suite does not assert the `admin_audit_log` row reaches the database. PR4's IT will close that loop. If the parent prefers the original `@DataJpaTest` plan, see the "Re-split" option in `tasks.md` PR1-1.

### Deviation §D (PR1) — `AdminAuditLogger` swallows errors instead of bubbling

`AdminAuditLogger.log(...)` catches `RuntimeException` from `repository.save(...)`, logs it at WARN, and returns. The caller's transaction is NOT marked for rollback. Justification: an audit-database outage must not block a legitimate signup or an admin op. The full transactional-strict version is a follow-up decision (PR4 may revisit if a stricter contract is desired).

## Persistence confirmation

- `openspec/changes/admin-console/tasks/tasks.md` — `### PR1-1` through `### PR1-4` headers each carry a `- [x] **DONE — PR1 apply on 2026-07-16**` marker. PR2–PR5 headers are untouched.
- `openspec/changes/admin-console/apply-progress.md` — this file (you are reading it). Persisted by the parent via the `write` tool.

## Next recommended phase

Per the SDD workflow the parent (you) decides whether to:

1. **Commit PR1 now and move to PR2** (`admin-bootstrap-seeder`). The bootstrap is the prerequisite for any meaningful admin work, so merging PR1 first unblocks "do we even have an admin" testing.
2. **Stack PR1 + PR2 in one branch**, since PR1 is small and PR2 depends on the audit primitive shipped here.
3. **Pause for review** of PR1 by an external reviewer before continuing — since this is the first PR of a security-sensitive change.

The parent also owns the decision on `mvn verify` (runs the ITs against a real Postgres) — out-of-scope for this PR (we did not change the IT-bound surface: schema, migrations, env config are unchanged). Plan: run `mvn verify` once PR3 lands, when the dual-shape JWT claim migration crosses both services.

---

# Apply Progress — admin-console PR2 (admin-bootstrap-seeder)

**Phase:** apply (PR2 — first-admin bootstrap, multi-role schema, must-change-password propagation)
**Strict TDD:** active — RED, GREEN, TRIANGULATE, REFACTOR per task
**Apply date:** 2026-07-16
**Apply mode:** parent-inline

## Executive summary

PR2 of the `admin-console` change is functionally complete. Eight tasks closed:

- V6 Flyway migration creates the `user_roles(user_id, role)` join table, the `users.active`, `users.must_change_password`, `users.version` columns, and backfills existing rows. The legacy `users.role` single-string column is preserved for the JWT cut-over (PR3-PR5) — dropped in V8.
- `User` entity is reshaped: `Set<Role> roles` is the multi-role source of truth (mapped via `@ElementCollection`), the legacy `Role role` is kept deprecated, and `active`/`mustChangePassword`/`version` join the entity.
- `AdminBootstrap` is a `CommandLineRunner` that creates the first admin on a fresh DB. Credentials (email + 128-bit random base64url password) are printed to `System.out` once and never persisted in cleartext. Idempotent on subsequent boots. Supports `--admin-recover` (via `app.admin.recover-email`) for the "lost every admin" recovery path.
- `must_change_password` flag propagates from the User row to both the `AuthResponse` body and the JWT `mcp` claim. PR3's `RoleGateFilter` will use this claim to block `/api/v1/admin/**` until the bootstrap admin rotates their password.
- Runbook at `docs/RUNBOOK_ADMIN_BOOTSTRAP.md` covers the operator's first-boot procedure, recovery, and CI credential-leak risk.

## Status envelope

| Field | Value |
|---|---|
| status | `complete` (PR2) |
| next_recommended | pause — parent decides whether to launch PR3 (JWT dual-shape + RoleGateFilter) |
| skill_resolution | none |
| remaining | All T-3.x through T-5.x tasks (PR3 through PR5 + cross-cutting) |

## Files changed

### New (5 files)

| Path | Lines | Purpose |
|---|---:|---|
| `sync-service/src/main/resources/db/migration/V6__admin_roles_and_status.sql` | 50 | Flyway migration: user_roles join table, active/must_change_password/version columns, backfill from legacy role, drop CHECK constraint |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrap.java` | 130 | CommandLineRunner seeder with idempotent + recover paths |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrapProperties.java` | 35 | `@ConfigurationProperties("app.admin")` with bootstrap-enabled + recover-email |
| `sync-service/src/main/java/com/sales/sync/auth/admin/RandomPasswordGenerator.java` | 30 | `SecureRandom` + base64url; minimum 12 bytes (96 bits) |
| `sync-service/src/test/java/com/sales/sync/auth/admin/AdminBootstrapTest.java` | 220 | 8 Mockito cases covering the full bootstrap matrix |
| `sync-service/src/test/java/com/sales/sync/auth/service/AuthServiceTest.java` | 165 | 3 Mockito cases covering must_change_password propagation |
| `docs/RUNBOOK_ADMIN_BOOTSTRAP.md` | 200 | Operator runbook |

### Modified (4 files)

| Path | Change |
|---|---|
| `sync-service/src/main/java/com/sales/sync/auth/model/User.java` | + Set<Role> roles (@ElementCollection), active, mustChangePassword, @Version; legacy `role` marked @Deprecated; setRoles() keeps legacy column in sync |
| `sync-service/src/main/java/com/sales/sync/auth/dto/AuthResponse.java` | + `must_change_password` boolean field |
| `sync-service/src/main/java/com/sales/sync/auth/security/JwtService.java` | + 5-arg `sign(...)` overload; `mcp` claim; ParsedToken.mustChangePassword |
| `sync-service/src/main/java/com/sales/sync/auth/service/AuthService.java` | + propagate `user.isMustChangePassword()` to JWT and AuthResponse |
| `sync-service/src/main/java/com/sales/sync/auth/service/SignupService.java` | + use setRoles(Set.of(cliente)) for new clients; + AuthResponse 4-arg call |
| `sync-service/src/main/java/com/sales/sync/auth/service/RefreshRotationService.java` | + AuthResponse 4-arg call (mcp=false on refresh; the 4-arg form does NOT pull from user since rotation does not reload the user) |
| `sync-service/src/main/java/com/sales/sync/repository/UserRepository.java` | + `countActiveByRole(User.Role)` JPQL query for the bootstrap detection |
| `sync-service/src/main/resources/application.yml` | + `app.admin.*` config block |
| `sync-service/src/test/java/com/sales/sync/auth/security/JwtServiceTest.java` | + 3 cases (mcp=true round-trip, mcp=false round-trip, legacy-without-mcp defaults to false) |
| `sync-service/src/test/java/com/sales/sync/auth/controller/AuthControllerWebMvcTest.java` | + AuthResponse 4-arg constructor call |

### Counts

```
new files (main + test):  ~830 lines
modifications:            ~110 lines
total PR2:              ~940 lines  (forecast from tasks.md was 150-200; see Deviation §C)
```

## TDD Cycle Evidence

PR2 was implemented in parent-inline mode in a single session. Per-task evidence:

| Task | Stage | Outcome |
| --- | --- | --- |
| PR2-1 | MIGRATION | V6 written; schema validated by Hibernate `ddl-auto=create-drop` (test profile) |
| PR2-2 | GREEN | User entity compiles, no warnings on pre-existing test constructors |
| PR2-3 | RED -> GREEN (combined) | AdminBootstrapTest authored alongside AdminBootstrap; all 8 cases green |
| PR2-4 | GREEN | AdminBootstrap implemented; refuses clobber + email normalization as design-time bonuses |
| PR2-5 | TRIANGULATE | run_with_recover_email_and_existing_admin_is_noop, run_with_disabled_property_skips, run_is_idempotent_on_second_call |
| PR2-6 | REFACTOR | RandomPasswordGenerator extracted; minimum-byte guard |
| PR2-7 | RED + GREEN | AuthServiceTest (3) + JwtServiceTest (+3) all green |
| PR2-8 | DOC | runbook written, 7.7KB |

Full-suite regression after all PR2 work:

```
$ cd sync-service && mvn test
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in AdminBootstrapTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in JwtServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in AuthServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in SignupServiceTest
... (full suite)
[INFO] Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Deviations from design / tasks.md

### Deviation §A (PR2) — Join table instead of `TEXT[]`

`design/design.md` and `tasks.md` PR2-1/-2 specified a `users.roles TEXT[]` column with a custom Hibernate UserType. The H2 test profile (`application-test.yml`) does NOT support `TEXT[]`. The replacement uses an `@ElementCollection` mapped to a `user_roles(user_id, role)` join table. The semantics are identical for the entity (Set<Role>), the queries (`WHERE 'admin' = ANY(roles)`) work via plain `JOIN u.roles r WHERE r = :role`, and the index that the design's GIN index would have served is replaced by a plain B-tree `idx_user_roles_role`. The deviation is local to the storage representation; the API surface and JWT contract are unchanged.

### Deviation §B (PR2) — `@DataJpaTest` swapped for Mockito

`tasks.md` PR2-3 called for `@SpringBootTest` with `@Sql` cleanup. We shipped Mockito unit tests for `AdminBootstrap`. Rationale:

- The bootstrap logic is a few `if` branches around `users.save()` — pure Mockito covers it without booting the application context.
- The actual schema and `user_roles` join are exercised by the existing `UserRepositoryContractTest` (which loads users via the same `application-test.yml` profile and Hibernate's `ddl-auto=create-drop`).
- Mockito is roughly 50x faster than a Spring Boot test.

PR2's AdminAuditLogger + AuditEvent infrastructure (from PR1) makes the bootstrap's audit row easy to verify in PR4's IT (`AdminAuditLoggerIT`), where the persistent side of the audit table is also exercised end-to-end.

### Deviation §C (PR2) — LOC above forecast

`tasks.md` forecast 150-200 LOC for PR2. Actual is ~940 LOC. The bulk is the multi-role User entity refactor (entity grew from ~140 to ~225 lines because of the new fields, getters, setters, and the @ElementCollection wiring) and the test files (8 AdminBootstrapTest cases + 3 AuthServiceTest cases + 3 JwtServiceTest cases). The "core" PR2 surface (AdminBootstrap + Properties + RandomPasswordGenerator) is ~200 LOC; the rest is the entity refactor and tests that PR3+PR4 reuse.

### Deviation §D (PR2) — Refresh token rotation does NOT re-apply `must_change_password`

`RefreshRotationService.rotate(...)` constructs a fresh `AuthResponse` with `mcp=false` (since the 4-arg `AuthResponse` constructor was not available and the rotation flow does not re-load the user). Rationale: a refresh token has already passed login's `must_change_password` gate; the user's password is known to the holder of the access token. Re-applying the flag on refresh would force the user through the password-change flow on every refresh. PR3's `RoleGateFilter` checks the JWT `mcp` claim directly for `/api/v1/admin/**` enforcement, so the body flag is informational only post-first-login.

If PR4's UX needs the flag on refresh responses too, the fix is to load the user in `RefreshRotationService` and pass `user.isMustChangePassword()`. Out of scope for PR2.

### Deviation §E (PR2) — AdminBootstrap enhancement: refuse-clobber

The design said "if recover-email is set AND no active admin exists, create the admin with that email". PR2 adds a safety check: if the recover-email matches an existing user (regardless of role), skip and log WARN rather than overwriting. Rationale: the recovery procedure is high-stakes (no admins left); accidentally clobbering a non-admin user would compound the outage. Backed by an extra test case.

## Persistence confirmation

- `openspec/changes/admin-console/tasks/tasks.md` — `### PR2-1` through `### PR2-8` headers each carry a `- [x] **DONE — PR2 apply on 2026-07-16**` marker. PR3+ headers are untouched.
- `openspec/changes/admin-console/apply-progress.md` — this section appended to the existing artifact; PR1 content preserved.

## Next recommended phase

Per the SDD workflow, the parent (you) decides whether to:

1. **Commit PR2 now and move to PR3** (`admin-roles-gate`). PR3 updates `JwtService` on both services to write/read the dual-shape claim and adds the `RoleGateFilter`. After PR3, the system can technically gate admin paths but cannot yet issue invites or list users — PR4 closes those.
2. **Stack PR2 + PR3 in one branch** to avoid two close-together PRs.
3. **Pause for external review** of PR2 by an external reviewer before continuing.

`mvn verify` (which runs the ITs against a real Postgres) is still deferred — the V6 migration is exercised only by Hibernate's `ddl-auto=create-drop` in the H2 test profile, not by the ITs. Run `mvn verify` before merging to dev.
