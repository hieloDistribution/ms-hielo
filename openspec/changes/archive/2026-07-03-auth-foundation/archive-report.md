# Archive Report — `auth-foundation`

| Field | Value |
|---|---|
| Change | `auth-foundation` |
| Closed | `2026-07-03` |
| Archive path | `openspec/changes/archive/2026-07-03-auth-foundation/` |
| Status | **archived — change closed** |
| Domain | `auth` (canonical spec lives at `openspec/specs/auth/spec.md`) |
| Total commits on the change branch | 3 (in stacked order) |
| skill_resolution | `none` |

---

## 1. What this change delivered

Two chained SDD slices — `auth-foundation-sync` (Batch 1) and `auth-foundation-order` (Batch 2) — that together bring a JWT-based authentication subsystem to the `hielo` monorepo:

- **`sync-service`** is now the identity issuer. It exposes `POST /api/v1/auth/login` and `POST /api/v1/auth/refresh`, manages refresh-token rotation with token-family theft detection, persists users + refresh tokens under Flyway (`V1__create_users_and_refresh_tokens.sql`), fail-fasts on missing or too-short `JWT_SECRET`, and applies a consistent error mapping (`{error: <code>}` JSON bodies) via `AuthExceptionHandler`.
- **`order-service`** is now a dependent resource server. It validates bearer access tokens locally against the same shared HMAC secret (`HS256`, `iss=hielo-sync`, `aud=hielo-order`) via a `OncePerRequestFilter`, populates a request-scoped `VendorContext`, fail-fasts on missing or too-short `JWT_SECRET`, and applies the equivalent `AuthExceptionHandler` JSON error mapping.
- **Cross-cutting invariants** (32-byte minimum `JWT_SECRET`, `vendor_id` claim is informational only, JSON entry-point body for unauthenticated access to protected paths) now apply uniformly across both services.

## 2. Commit chain on `feature/auth-foundation`

```
e959ea3 chore(spec): promote auth-foundation to canonical auth spec
0219920 fix(auth): close sdd-verify gaps and resolve spec S3 contradiction
ad54f80 feat(auth): auth-foundation-order — local HS256 JWT validation
f0ee6a6 feat(auth): auth-foundation-sync — JWT login + refresh rotation
```

The first two commits land the implementation; the third lands sdd-verify gap closures (test fixes, 4 new test files, sibling-rotation test, sync-service failsafe binding, project-root `.tool-versions`); the fourth promotes the delta spec to canonical. The two `feat(auth):` commits form the intended chained-PR shape and can be pushed as two PRs against `main` in stack order (Batch 1 / sync first, Batch 2 / order second).

## 3. Spec coverage delivered (per `verify-report.md` §3, post-fix)

- All twelve spec scenarios (S1–S7, O1–O3) have at least one passing test.
- Five cross-cutting scenarios / advisories (X1, X2, X3) are covered at unit / slice / boot level (X3 is deferred by design — no authorization decision to assert against).
- `AccountLockedException` → HTTP 423 + `{"error":"account_locked"}` is the locked source-of-truth in spec, design, and implementation.

## 4. Test count delivered

| Module | Surefire | Failsafe | Total |
|---|---:|---:|---:|
| `sync-service` | 26 | 14 | **40** |
| `order-service` | 14 | 5 | **19** |
| **Combined** | **40** | **19** | **59** |

59 / 59 green. Surefire + Failsafe are now correctly bound in both modules' poms (the original gap on `sync-service` was closed in commit `0219920`).

## 5. Surprises and lessons recorded

- **Sync-service `*IT.java` were silently never running** with `mvn verify` because `sync-service/pom.xml` did not bind `maven-failsafe-plugin`. The first verify run therefore under-reported IT coverage. `0219920` adds the binding; this is now mechanically impossible to recur.
- **One test was deterministic-but-non-deterministic**: `JwtServiceTest.parse_rejects_tampered_signature` flipped the **last** character of a JWS compact serialization whose last base64 char only encodes 4 of 6 bits. When the flip landed in the 2 ignored bits, the decoded signature bytes were identical and the HMAC still verified. Fixed by mutating a char in the **middle** of the signature segment; comment in the test now documents the reasoning.
- **`AccountLockedException` was inconsistent across spec / design / implementation** before this cycle. Resolution: align spec to design + implementation (HTTP 423 + `account_locked`). HTTP 423 is more accurate (RFC 4918 §11.3) and the body token follows the rest of the project's `snake_case` convention. Documented inline in `specs/auth/spec.md` with a source-of-truth note.
- **Toolchain preflight required**: the runtime Java was 26.0.1 but the project and its Mockito-inline mock-maker need 21. Closed by adding `.tool-versions` to the repo root (`java 21.0.2`).

## 6. Acknowledged follow-ups (out of scope for this change)

These are recorded but **not** part of this archive — each is a candidate for a follow-up SDD change.

| # | Item | Where | Suggested change name |
|---|---|---|---|
| 1 | `AuthController.login` has its own `try/catch` and `System.err.println("[DEBUG-CTRL] …")`; `AuthExceptionHandler` already maps the same exceptions. | `sync-service/src/main/java/com/sales/sync/auth/controller/AuthController.java` | `chore-auth-cleanup-controller-error-mapping` |
| 2 | `order-service/src/test/resources/application-test.yml` uses `ddl-auto: create-drop` rather than the Flyway migration that `design.md` §3.2 anticipates. | `order-service/src/test/resources/application-test.yml` | `chore-orders-flyway-test-bootstrap` |
| 3 | `JWT_SECRET` injected via `maven-surefire-plugin` / `maven-failsafe-plugin` `<systemPropertyVariables>`. `JwtSecretEnvironmentPostProcessor` runs before `application*.yml`. Consider an `@TestPropertySource`-based approach. | `sync-service/pom.xml`, `order-service/pom.xml` | `chore-tests-jwt-secret-injection-via-testsource` |
| 4 | Order-service BootIT has no dedicated `expired_token` test (covered at unit and slice level only); X3 (`vendor_id` informational only) has no executable guard today (deferred by spec design — no authorization decision exists yet). | `order-service/src/test/java/.../OrderServiceBootIT.java` | `test-orders-bootit-coverage` |

## 7. PR shape and review

The change is designed to ship as **two chained PRs**:

- **PR 1 — `auth-foundation-sync`** (commits `f0ee6a6` + `0219920` + `e959ea3` changes for the sync module only).
- **PR 2 — `auth-foundation-order`** (commits `ad54f80` + the order-side portion of `0219920`).

The 4R pre-PR review still applies before `git push` / `gh pr create`:

- `review-risk` — security, JWT secret handling, masking, password hashing cost, Flyway migrations.
- `review-reliability` — token family burn semantics, refresh-rotation atomicity, fail-fast behaviour.
- `review-readability` — recommended because of the dev `println` in `AuthController.login` (§6 follow-up #1); not required for the merge.

`review-resilience` is incidental here (no external fallback paths introduced).

## 8. Files in this archive

```
openspec/changes/archive/2026-07-03-auth-foundation/
├── archive-report.md         (this file)
├── design.md                 (from sdd-design phase)
├── explore.md                (from sdd-explore phase)
├── proposal.md               (from sdd-proposal phase)
├── specs/                    (delta spec — superseded by openspec/specs/auth/spec.md)
├── sync-report.md            (from sdd-sync phase — status: synced)
├── tasks.md                  (from sdd-tasks phase)
└── verify-report.md          (from sdd-verify phase — status: PASS, post-fix)
```

## 9. skill_resolution

`skill_resolution: none` — no project/user skills were injected by the parent orchestrator for any of the SDD phases in this change. The sdd-{explore,proposal,spec,design,tasks,apply,verify,sync,archive} agents carry their own runtime contracts (per `~/.pi/agent/agents/sdd-*.md`); the registry at `/home/hat/projects/hielo/.atl/skill-registry.md` did not surface any task-relevant path.

---

## 10. Lifecycle closed

The `auth-foundation` change is now **archived**. The `auth` domain is canonical at `openspec/specs/auth/spec.md`. Next time a change touches auth, its delta spec will use the standard `## ADDED Requirements` / `## MODIFIED Requirements` / `## REMOVED Requirements` sections to diff against this canonical file.
