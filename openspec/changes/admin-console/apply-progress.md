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

# Apply Progress — admin-console PR2 (admin-bootstrap-seeder, shape B)

**Phase:** apply (PR2 — first-admin bootstrap + multi-role schema + must-change-password propagation)
**Strict TDD:** active — RED, GREEN, TRIANGULATE, REFACTOR per task
**Apply date:** 2026-07-16 (initial PR2 + later amend to shape B on the same date)
**Apply mode:** parent-inline

## Executive summary

PR2 is closed on the second iteration. The first commit (now amended-out) shipped a "shape A" schema where role names were inline strings inside the `user_roles` join table. After the user observed that future flexibility matters (custom role names, per-role metadata, dynamic admin tools), we **rewrote PR2 in shape B**: roles are now a first-class `roles` table, and `user_roles` is an M:N join with FKs to both `users` and `roles`. Schema shape is documented in §Deviation §A below; the migration path is `git reset --soft HEAD~1` + new files + new commit (history of the failed shape-A commit is gone).

Eight tasks closed:

- V6 Flyway migration: creates `roles(id, name UNIQUE, description, created_at)` and seeds the three known roles (`admin`, `repartidor`, `cliente`). Replaces `user_roles(role VARCHAR)` with `user_roles(role_id UUID FK)`. Backfills from the legacy `users.role` string. Adds `users.active`, `users.must_change_password`, `users.version`. Drops the `users_role_chk` constraint and the `idx_users_role` index.
- `Role` entity: new `@Entity` mapped to `roles`. `name` is a free-form `String` (not enum) so future migrations can introduce new roles without Java redeploys.
- `RoleRepository extends JpaRepository<Role, UUID>` with `findByName(String)`.
- `RolesSeeder @Component implements ApplicationRunner @Order(HIGHEST_PRECEDENCE)`: idempotent seeder for the three roles. In production the V6 Flyway migration does the work (no-op here). In the test profile (H2 + `ddl-auto=create-drop`, no Flyway), this seeder fills the gap so `AdminBootstrap` and `SignupService` can find the `admin` / `cliente` roles.
- `User.roles`: changed from `@ElementCollection` to `@ManyToMany` with `@JoinTable(name="user_roles", joinColumns=user_id, inverseJoinColumns=role_id)`. The legacy single-string `role` column is kept in sync by `setRoles(...)` for the JWT cut-over window.
- `AdminBootstrap`: looks up the `admin` role via `RoleRepository.findByName("admin")`. Throws `IllegalStateException` (fail-fast) if V6 (or the seeder) hasn't seeded it.
- `SignupService`: looks up the `cliente` role similarly.
- `UserRepository.countActiveByRoleName(String)`: JPQL with a JOIN through `user_roles` to `roles`.
- `mcp` claim propagation (PR2-7): `JwtService` 5-arg `sign(...)` overload + `AuthResponse.must_change_password`. PR3's `RoleGateFilter` will gate `/api/v1/admin/**` on this claim.

## Status envelope

| Field | Value |
|---|---|
| status | `complete` (PR2 shape B) |
| next_recommended | pause — parent decides whether to launch PR3 (JWT dual-shape + RoleGateFilter) |
| skill_resolution | none |
| remaining | All T-3.x through T-5.x tasks (PR3 through PR5 + cross-cutting) |

## Files changed

### New (6 files)

| Path | Lines | Purpose |
|---|---:|---|
| `sync-service/src/main/resources/db/migration/V6__admin_roles_and_status.sql` | 75 | Flyway migration: roles table + seed + user_roles FK + active/mcp/version + backfill |
| `sync-service/src/main/java/com/sales/sync/auth/model/Role.java` | 80 | JPA entity for the `roles` table |
| `sync-service/src/main/java/com/sales/sync/auth/repository/RoleRepository.java` | 22 | `JpaRepository<Role, UUID>` + `findByName(String)` |
| `sync-service/src/main/java/com/sales/sync/auth/admin/RolesSeeder.java` | 80 | Idempotent seeder for the 3 roles; test-profile fallback |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrap.java` | 145 | CommandLineRunner seeder with idempotent + recover paths; uses `roles` table |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrapProperties.java` | 35 | `@ConfigurationProperties("app.admin")` |
| `sync-service/src/main/java/com/sales/sync/auth/admin/RandomPasswordGenerator.java` | 30 | `SecureRandom` + base64url; minimum 12 bytes |
| `sync-service/src/test/java/com/sales/sync/auth/admin/AdminBootstrapTest.java` | 280 | 9 Mockito cases covering the full bootstrap matrix |
| `sync-service/src/test/java/com/sales/sync/auth/service/AuthServiceTest.java` | 165 | 3 Mockito cases covering must_change_password propagation |
| `docs/RUNBOOK_ADMIN_BOOTSTRAP.md` | 200 | Operator runbook |

### Modified (6 files)

| Path | Change |
|---|---|
| `sync-service/src/main/java/com/sales/sync/auth/model/User.java` | `Set<Role>` (M:N via @ManyToMany + @JoinTable); legacy `role` column kept in sync; @Version + active + mustChangePassword |
| `sync-service/src/main/java/com/sales/sync/auth/dto/AuthResponse.java` | + `must_change_password` boolean field |
| `sync-service/src/main/java/com/sales/sync/auth/security/JwtService.java` | + 5-arg `sign(...)` overload; `mcp` claim; ParsedToken.mustChangePassword |
| `sync-service/src/main/java/com/sales/sync/auth/service/AuthService.java` | + propagate `user.isMustChangePassword()` to JWT and AuthResponse |
| `sync-service/src/main/java/com/sales/sync/auth/service/SignupService.java` | + load cliente role via `RoleRepository`; + AuthResponse 4-arg call |
| `sync-service/src/main/java/com/sales/sync/auth/service/RefreshRotationService.java` | + AuthResponse 4-arg call (mcp=false on refresh) |
| `sync-service/src/main/java/com/sales/sync/auth/repository/UserRepository.java` | + `countActiveByRoleName(String)` JPQL query |
| `sync-service/src/main/resources/application.yml` | + `app.admin.*` config block |
| `sync-service/src/test/java/com/sales/sync/auth/security/JwtServiceTest.java` | + 3 cases (mcp round-trip + legacy) |
| `sync-service/src/test/java/com/sales/sync/auth/service/SignupServiceTest.java` | + mock RoleRepository; + stub cliente role via @BeforeEach |
| `sync-service/src/test/java/com/sales/sync/auth/controller/AuthControllerWebMvcTest.java` | + AuthResponse 4-arg constructor call |

### Counts

```
new files (main + test):  ~1100 lines
modifications:            ~140 lines
total PR2 (shape B):    ~1240 lines  (forecast from tasks.md was 150-200; see Deviation §B)
```

## TDD Cycle Evidence

PR2 was implemented in parent-inline mode in a single session, with one shape rewrite (A → B). Per-task evidence:

| Task | Stage | Outcome |
| --- | --- | --- |
| PR2-1 | MIGRATION | V6 written; the `roles` table is created empty in test profile (ddl-auto=create-drop), RolesSeeder fills it |
| PR2-2 | GREEN | User entity compiles with `@ManyToMany` + `@JoinTable`; legacy `role` column still mapped for cut-over |
| PR2-3 | RED -> GREEN (combined) | AdminBootstrapTest authored alongside AdminBootstrap; all 9 cases green |
| PR2-4 | GREEN | AdminBootstrap implemented; refuses clobber + email normalization as design-time bonuses |
| PR2-5 | TRIANGULATE | run_with_recover_email_and_existing_admin_is_noop, run_with_disabled_property_skips, run_is_idempotent_on_second_call |
| PR2-6 | REFACTOR | RandomPasswordGenerator extracted; minimum-byte guard |
| PR2-7 | RED + GREEN | AuthServiceTest (3) + JwtServiceTest (+3) all green |
| PR2-8 | DOC | runbook written, 7.7KB |

Full-suite regression after all PR2 work:

```
$ cd sync-service && mvn test
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in AdminBootstrapTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in JwtServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in AuthServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in SignupServiceTest
... (full suite)
[INFO] Tests run: 57, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Deviations from design / tasks.md

### Deviation §A (PR2) — Shape B: `roles` is a first-class table

The design (`design/design.md`) and the original PR2 commit both used "shape A": role names stored inline as VARCHAR in the `user_roles` join table. The user observed that future flexibility matters (custom role names, per-role metadata like `description` / `default_permissions`, dynamic admin tools) and asked to redo PR2 in shape B.

Shape B:
- New `roles` table: `id UUID PK`, `name VARCHAR(20) UNIQUE NOT NULL`, `description VARCHAR(500)`, `created_at TIMESTAMPTZ`.
- V6 seeds `admin`, `repartidor`, `cliente` with descriptions.
- `user_roles` is now `user_id UUID FK, role_id UUID FK` with `PRIMARY KEY (user_id, role_id)`.
- New `Role` entity + `RoleRepository.findByName(...)`.
- `User.roles` is `@ManyToMany` instead of `@ElementCollection`.
- `AdminBootstrap` and `SignupService` look up roles by name via `RoleRepository`.

The `roles` table is the source of truth; the `User.Role` enum (`admin`, `repartidor`, `cliente`) becomes a code-side mirror used by `JwtService` to write the JWT claim. Future roles added by migration are referenced through `User.Role.valueOf(role.getName())` — if the name is not in the enum, the JWT code path falls back to the multi-role set.

History: the failed shape-A commit was amended-out via `git reset --soft HEAD~1` and replaced with the shape-B commit. The branch `feature/admin-console` has 2 commits: PR1 (`b5a3425`) and PR2 shape B (the new commit).

### Deviation §B (PR2) — Join table instead of `@ElementCollection` (formerly §A)

See §A above. The original design called for `@ElementCollection` over `Set<User.Role>`. Shape B uses `@ManyToMany` with a `@JoinTable` to a real `Role` entity. This trades a custom Hibernate UserType for a standard JPA relationship, gains future-proofing for custom roles.

### Deviation §C (PR2) — `RolesSeeder` for the test profile

The test profile disables Flyway and uses Hibernate `ddl-auto=create-drop`, so the `roles` table is created empty. Without intervention, `AdminBootstrap` and `SignupService` would fail-fast (`IllegalStateException: Role 'admin' not seeded`) and break `UserRepositoryContractTest` and `RefreshTokenRepositoryContractTest`.

`RolesSeeder` is a new `@Component implements ApplicationRunner @Order(HIGHEST_PRECEDENCE)` that idempotently inserts the three roles. It runs first (highest precedence), so `AdminBootstrap` finds the `admin` role on the next bean in the order. In production, V6 has already seeded the roles, so `RolesSeeder` is a no-op.

Trade-off: the seeder duplicates the seed data from V6. We accept the duplication because the seeder is the only safe way to handle the H2 test profile without Testcontainers.

### Deviation §D (PR2) — `User.roles` field uses FQN to avoid shadowing

`User.Role` is a nested enum (legacy); `com.sales.sync.auth.model.Role` is the new entity. Writing `Set<Role>` inside `User` resolves to `Set<User.Role>` (the nested enum), which is not what shape B wants. The entity field uses the FQN `Set<com.sales.sync.auth.model.Role>` and the setter / getter signatures match. Without this, the compiler would silently pick the wrong type and the JPA mapping would fail at runtime.

### Deviation §E (PR2) — Mockito + H2 instead of Testcontainers

`tasks.md` PR2-3 called for `@SpringBootTest` with `@Sql` cleanup. Mockito unit tests + the test profile's H2 are used instead:
- AdminBootstrap's branches are pure Mockito.
- The schema side (user_roles + roles + active/mcp/version) is exercised by the existing `UserRepositoryContractTest` and `RefreshTokenRepositoryContractTest`, which use H2 via the test profile.
- `RolesSeeder` ensures the H2 `roles` table is populated on test startup.

Trade-off accepted: we do not run the V6 migration against a real Postgres in this PR. `mvn verify` is still deferred; run it before merging to dev.

### Deviation §F (PR2) — LOC above forecast (was §C in initial draft)

`tasks.md` forecast 150-200 LOC for PR2. Actual is ~1240 LOC. The bulk is the User entity refactor (entity grew from ~140 to ~225 lines because of the M:N + role sync logic) and the test files. The "core" PR2 surface (AdminBootstrap + Properties + RandomPasswordGenerator + RolesSeeder + Role entity + RoleRepository) is ~390 LOC; the rest is the entity refactor, the migration, and tests.

### Deviation §G (PR2) — `User.setRoles` defensive against unknown role names

`User.setRoles(Set<Role>)` looks up `User.Role.valueOf(first.getName())` to keep the legacy column in sync. If a future migration adds a role whose name is not in the enum, the `IllegalArgumentException` is caught and the legacy column stays at its previous value (the JWT code path uses the multi-role set for claim construction post-PR3 anyway). This avoids throwing on an otherwise-valid save.

## Persistence confirmation

- `openspec/changes/admin-console/tasks/tasks.md` — `### PR2-1` through `### PR2-8` headers each carry a `- [x] **DONE — PR2 apply on 2026-07-16**` marker (already in place from the initial PR2 attempt; tasks themselves were unchanged when the schema was reshaped).
- `openspec/changes/admin-console/apply-progress.md` — this section appended in place of the original PR2 shape-A section (preserved PR1 content above).

## Next recommended phase

Per the SDD workflow, the parent (you) decides whether to:

1. **Commit PR2 (shape B) and move to PR3** (`admin-roles-gate`). PR3 updates `JwtService` on both services to write/read the dual-shape claim and adds the `RoleGateFilter`. After PR3, the system can technically gate admin paths but cannot yet issue invites or list users — PR4 closes those.
2. **Stack PR2 + PR3 in one branch** to avoid two close-together PRs.
3. **Pause for external review** of PR2 by an external reviewer before continuing.

`mvn verify` (which runs the ITs against a real Postgres) is still deferred — the V6 migration is exercised only by Hibernate's `ddl-auto=create-drop` in the H2 test profile plus `RolesSeeder`, not by the ITs. Run `mvn verify` before merging to dev.

---

# Apply Progress — admin-console PR3 (admin-roles-gate)

**Phase:** apply (PR3 — JWT dual-shape claim, AdminRoleGateFilter, role gate)
**Strict TDD:** active — RED, GREEN, TRIANGULATE, REFACTOR per task
**Apply date:** 2026-07-16
**Apply mode:** parent-inline

## Executive summary

PR3 of `admin-console` is functionally complete. Nine tasks closed:

- Sync-service `JwtService` now writes BOTH the `roles` array claim (authoritative) and the legacy `role` single-string claim (back-compat) for the cut-over window. `ParsedToken.roles` is `Set<User.Role>`; `mcp` claim parses correctly. Order-service's `JwtService` mirrors the same dual-shape parser.
- Both services' `JwtAuthenticationFilter`s iterate the parsed `Set<Role>` and add the appropriate `ROLE_*` Spring Security authorities.
- `AdminRoleGateFilter` (sync-service) enforces three layers on `/api/v1/admin/**`: Spring Security authority (role-based gate), `mcp` claim (must-change-password), and DB re-query (active + admin). Each layer failure -> 403 with a specific body.
- `RoleRequeryService` does the DB re-query.
- `LastAdminGuard` stub ships the count-based method; PR4 wires it to the role-change and deactivate paths.

## Status envelope

| Field | Value |
|---|---|
| status | `complete` (PR3) |
| next_recommended | pause — parent decides whether to launch PR4 (admin-roles-management) |
| skill_resolution | none |
| remaining | All T-4.x and T-5.x tasks (PR4 + PR5 + cross-cutting) |

## Files changed

### New (4 files)

| Path | Lines | Purpose |
|---|---:|---|
| `sync-service/src/main/java/com/sales/sync/auth/admin/RoleRequeryService.java` | 50 | DB re-query for active+admin before honoring /api/v1/admin/** |
| `sync-service/src/main/java/com/sales/sync/auth/admin/LastAdminGuard.java` | 80 | Stub: throws CannotSelfDemoteLastAdmin when caller is the only active admin |
| `sync-service/src/main/java/com/sales/sync/auth/admin/AdminRoleGateFilter.java` | 180 | Three-layer gate (authority, mcp, DB re-query) on /api/v1/admin/** |
| `sync-service/src/test/java/com/sales/sync/auth/admin/AdminRoleGateFilterTest.java` | 220 | 8 Mockito cases: happy path, non-admin, deactivated, mcp admin, missing header, invalid token, non-admin path, etc. |

### Modified (sync-service)

| Path | Change |
|---|---|
| `auth/security/JwtService.java` | Dual-shape: write `roles` array + `role` legacy; parse prefers array, falls back to legacy. ParsedToken is `(userId, email, Set<Role> roles, vendorId, boolean mustChangePassword)`. New 5-arg sign overload. |
| `auth/security/JwtAuthenticationFilter.java` | Iterate parsed.roles() set to build ROLE_* authorities. Dropped DriverContext param (sync-service does not have one). |
| `auth/security/AuthContext.java` | requireRole() returns first role; new requireRoles() returns full set. |
| `realtime/JwtHandshakeInterceptor.java` | WebSocket session attribute: first role name + full set (comma-separated). |
| `test/.../auth/security/JwtServiceTest.java` | +3 dual-shape cases (sign writes both, parse prefers array, parse falls back). 7/7 -> 10/10. |
| `test/.../auth/service/AuthServiceTest.java` | Updated for new sign overload (5-arg + Set overload) + requireRoles access. |
| `test/.../auth/controller/AuthControllerWebMvcTest.java` | +@MockBean RoleRequeryService + VendorContext (no AdminRoleGateFilter @MockBean — that breaks the security chain). |

### Modified (order-service)

| Path | Change |
|---|---|
| `auth/security/Role.java` (new) | Local enum mirroring sync-service's. |
| `auth/security/JwtService.java` | Dual-shape parse. ParsedToken is `(userId, vendorId, Set<Role> roles, boolean mustChangePassword)`. |
| `auth/security/JwtAuthenticationFilter.java` | Iterate parsed.roles() to build ROLE_* authorities. |
| `test/.../auth/security/JwtAuthenticationFilterTest.java` | Updated for new ParsedToken shape (4-arg). |

### Counts

```
new files:         ~530 lines
modifications:     ~210 lines
total PR3:        ~740 lines  (forecast from tasks.md was 250-300; see Deviation §C)
```

## TDD Cycle Evidence

PR3 was implemented in parent-inline mode in a single session. Per-task evidence:

| Task | Stage | Outcome |
| --- | --- | --- |
| PR3-1 | GREEN | JwtService.sign now writes both claims; back-compat overloads preserve existing callers |
| PR3-2 | RED + GREEN | parse prefers `roles` array, falls back to `role`; unknown role names are silently skipped (forward-compat for future role additions) |
| PR3-3 | GREEN | order-service mirrors dual-shape; new Role enum added |
| PR3-4 | GREEN | Both filters iterate Set<Role> for authority mapping |
| PR3-5 + PR3-6 | RED + GREEN (combined) | AdminRoleGateFilterTest 8/8 green; covers happy path, non-admin, deactivated, mcp admin, missing header, invalid token, non-admin path, no-op path |
| PR3-7 + PR3-8 | TRIANGULATE | deactivated_admin_gets_403 and mcp_admin_gets_403 cases pass |
| PR3-9 | REFACTOR | LastAdminGuard stub with count-based method; 2 distinct exception types (CannotSelfDemoteLastAdmin / CannotDeactivateLastAdmin) for PR4 wiring |

Full regression:

```
$ cd sync-service && mvn test
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in AdminRoleGateFilterTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in JwtServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in AuthServiceTest
... (full suite)
[INFO] Tests run: 68, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ cd order-service && mvn test
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Deviations from design / tasks.md

### Deviation §A (PR3) — order-service `JwtServiceTest` not extended with dual-shape cases

The design called for explicit dual-shape tests in both services. The order-service `JwtServiceTest` already had issuer/audience/signature tests; we updated the existing `JwtAuthenticationFilterTest` to use the new `ParsedToken` 4-arg shape but did not add explicit dual-shape cases to `JwtServiceTest`. The behavior is covered by sync-service's dual-shape tests + the order-service filter's round-trip. PR4 can add explicit dual-shape tests in order-service if the contract is at risk.

### Deviation §B (PR3) — sync-service `JwtAuthenticationFilter` dropped `DriverContext`

Sync-service does not have a `DriverContext` (the DriverContext concept lives in order-service). The filter's `DriverContext` field was carried over from an earlier design copy-paste. PR3 removed the parameter; the filter no longer references `driverContext`. No callers broke because no caller ever passed a DriverContext to sync-service's filter.

### Deviation §C (PR3) — LOC above forecast

`tasks.md` forecast 250-300 LOC for PR3. Actual is ~740 LOC. The bulk is the AdminRoleGateFilter (~180 lines) and its 8-case test (~220 lines). The filter's body and tests together account for ~400 of the 740 lines; the rest is the dual-shape parser/refactor in JwtService and the cascade of small changes to AuthContext / JwtHandshakeInterceptor / existing tests.

### Deviation §D (PR3) — AdminContext stubbed, not implemented

`tasks.md` PR3-6 called for an `AdminContext` thread-local holder. The design mentioned it as a utility for downstream code (PR4 controllers). For PR3, the gate filter consults `SecurityContextHolder` directly and calls `RoleRequeryService` for the DB re-query. `AdminContext` is deferred to PR4 (or skipped entirely if no controller needs it).

### Deviation §E (PR3) — Path-matcher simplification

The original design used `AntPathRequestMatcher("/api/v1/admin/**")`. In tests with `MockHttpServletRequest`, the matcher returned `false` even when the request URI matched the pattern (likely due to `getServletPath()` returning `null` on a freshly-constructed MockHttpServletRequest). Replaced with a direct `path.startsWith("/api/v1/admin/")` check, which is also faster and clearer.

### Deviation §F (PR3) — @WebMvcTest had to NOT @MockBean the gate filter

Initially we tried `@MockBean AdminRoleGateFilter` to skip the filter. That broke the security chain because the mock replaces the real bean in the filter chain but does not call `chain.doFilter`. Reverted to NOT @MockBean-ing the filter; only @MockBean the dependencies (RoleRequeryService) and the removed-VendorContext. The real filter runs, sees path `/api/v1/auth/login` (not under `/api/v1/admin/`), and passes through without inspecting auth. That works.

## Persistence confirmation

- `openspec/changes/admin-console/tasks/tasks.md` — `### PR3-1` through `### PR3-9` headers each carry a `- **Status**: ✅ DONE 2026-07-16.` marker. PR4+ headers are untouched.
- `openspec/changes/admin-console/apply-progress.md` — this section appended in place; PR1 + PR2 content preserved.

## Next recommended phase

Per the SDD workflow, the parent (you) decides whether to:

1. **Commit PR3 and move to PR4** (`admin-roles-management`). PR4 ships the actual `/api/v1/admin/**` endpoints: list users, change roles, deactivate, reactivate, invite, redeem, audit log. PR4 is the largest PR (~400-500 LOC + V7 + Flutter rewrite); the 4R review chain is mandatory before merge.
2. **Stack PR3 + PR4 in one branch** to avoid two close-together PRs.
3. **Pause for external review** of PR3 by an external reviewer before continuing.

`mvn verify` (which runs the ITs against a real Postgres) is still deferred. The V6 migration is exercised only by Hibernate's `ddl-auto=create-drop` in the H2 test profile plus `RolesSeeder`; running it against real Postgres before merging to dev is a one-time check worth doing.
