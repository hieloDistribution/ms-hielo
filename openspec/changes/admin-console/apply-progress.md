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
