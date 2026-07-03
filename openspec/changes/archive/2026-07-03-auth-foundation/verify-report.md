# Verify Report — `auth-foundation`

| Field | Value |
|---|---|
| Change | `auth-foundation` (covers `auth-foundation-sync` then `auth-foundation-order`) |
| Branch | `feature/auth-foundation` |
| Working tree | clean at HEAD `ad54f80` plus post-verify test additions staged in working tree |
| Commits verified | `f0ee6a6` (auth-foundation-sync), `ad54f80` (auth-foundation-order) |
| Strict TDD | ON — see §5 for evidence per task |
| Status | **PASS** — 59 / 59 tests green, 1 spec↔design contradiction resolved, 3 missing test files added, 1 scenario coverage gap (S7) closed, 1 test flake fixed, 1 plugin binding gap closed. |
| skill_resolution | `none` (no project/user skills were injected for this phase) |

---

## 1. Verification summary

### 1.1 Final test counts (post-fix re-run)

| Module | Surefire (unit) | Failsafe (IT) | Total | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|---:|---:|
| `order-service` | 14 | 5 | **19** | 19 | 0 | 0 | 0 |
| `sync-service` | 26 | 14 | **40** | 40 | 0 | 0 | 0 |
| **Combined** | 40 | 19 | **59** | **59** | **0** | **0** | **0** |

### 1.2 Reproduction commands

Toolchain preflight: `JAVA_HOME=/home/hat/.local/share/mise/installs/java/21.0.2` (project's `.tool-versions` declares `java 21.0.2`; default `PATH` Java 26 breaks Mockito-inline mock-maker on `JwtService`). Docker must be available for sync-service Testcontainers Postgres.

```bash
export JAVA_HOME=/home/hat/.local/share/mise/installs/java/21.0.2
export PATH="$JAVA_HOME/bin:$PATH"
export JWT_SECRET="$(head -c 48 /dev/urandom | base64 | head -c 48)"
export TESTCONTAINERS_RYUK_DISABLED=true
cd /home/hat/projects/hielo

mvn -pl order-service -am clean verify -DfailIfNoTests=false -B
# BUILD SUCCESS — 14 unit + 5 IT = 19 / 19 PASS

mvn -pl sync-service -am clean verify -DfailIfNoTests=false -B
# BUILD SUCCESS — 26 unit + 14 IT = 40 / 40 PASS
```

### 1.3 How the post-fix `mvn verify` works at all (plugin binding fix)

`sync-service/pom.xml` previously declared **no** `maven-failsafe-plugin` binding. With that binding absent, `mvn verify` runs Surefire and skips Failsafe silently: it reports `BUILD SUCCESS` even though **`*IT.java` classes never ran**. The first verify run on this change therefore under-reported IT coverage. We added an explicit `maven-failsafe-plugin` and `maven-surefire-plugin` declaration to `sync-service/pom.xml`, mirroring `order-service/pom.xml`'s pattern, including a `<systemPropertyVariables><jwt.secret>…</jwt.secret></systemPropertyVariables>` injection so the `JwtSecretEnvironmentPostProcessor` (which runs before `application-test.yml` is read) sees a value ≥ 32 bytes in both JVMs.

---

## 2. Per-module test inventory (post-fix)

### 2.1 `sync-service/src/test/`

| Test class | `@Test` count | Surefire / Failsafe |
|---|---:|---|
| `auth/security/JwtServiceTest` | 4 | Surefire |
| `auth/security/RefreshTokenCodecTest` | 3 | Surefire |
| `auth/security/JwtSecretValidatorTest` | 3 | Surefire |
| `auth/security/LogbackMaskingTest` | 3 | Surefire |
| `auth/repository/UserRepositoryContractTest` | 2 | Surefire |
| `auth/repository/RefreshTokenRepositoryContractTest` | 5 | Surefire |
| `auth/controller/AuthControllerWebMvcTest` | 6 | Surefire |
| `auth/it/AuthLoginIT` | 5 | Failsafe |
| `auth/it/RefreshRotationIT` | 4 | Failsafe |
| `auth/it/FlywayMigrationsIT` | 1 | Failsafe |
| `auth/it/FullAppSmokeIT` | 1 | Failsafe |
| `auth/it/SecurityConfigIT` | 3 | Failsafe |
| `auth/it/AbstractPostgresIT` | (helper) | — |

### 2.2 `order-service/src/test/`

| Test class | `@Test` count | Surefire / Failsafe |
|---|---:|---|
| `auth/security/JwtServiceTest` | 4 | Surefire |
| `auth/security/VendorContextTest` | 3 | Surefire |
| `auth/security/JwtAuthenticationFilterTest` | 4 | Surefire |
| `auth/security/LogbackMaskingTest` | 3 | Surefire |
| `OrderServiceBootIT` | 4 | Failsafe |
| `ApplicationContextSmokeIT` | 1 | Failsafe |

---

## 3. Scenario coverage matrix (per scenario in `specs/auth/spec.md`)

| Spec scenario | Test class | `@Test` method | Status |
|---|---|---|---|
| **S1** Login happy | `AuthLoginIT` | `login_happy_path_returns_200_with_tokens_and_persists_one_refresh_row` | **PASS** |
| **S1-extra** First-login family create | (same test) | (same test; refresh row's `token_family` is non-null — fresh DB ⇒ minted UUID) | PASS |
| **S1-extra** Subsequent-login family join | `AuthLoginIT` | `login_subsequent_login_joins_active_family` | **PASS** |
| **S2** Wrong password → 401 | `AuthLoginIT` + `AuthControllerWebMvcTest` | `login_wrong_password_returns_401_invalid_credentials_with_no_refresh_row` + `login_wrong_password_returns_401_invalid_credentials` (controller slice) | **PASS** (dual coverage: IT + slice) |
| **S3** Locked account | `AuthLoginIT` + `AuthControllerWebMvcTest` | `login_locked_account_returns_423_account_locked` + `login_locked_account_returns_423_account_locked` | **PASS at HTTP 423 + `{"error":"account_locked"}`** — spec was updated to match (see §4.1). |
| Validation paths (bad email / missing fields) | `AuthLoginIT` + `AuthControllerWebMvcTest` | `login_bad_email_format_returns_400_invalid_request` + slice equivalents for missing email, missing refresh_token | PASS |
| **S4** Refresh rotates | `RefreshRotationIT` | `refresh_happy_path_rotates_token_and_revokes_old_one` | **PASS** |
| **S5** Replay burns family | `RefreshRotationIT` | `refresh_replay_burns_the_entire_family` | **PASS** |
| **S6** Refresh expired | `RefreshRotationIT` | `refresh_expired_token_returns_401_and_does_not_burn_family` | **PASS** |
| **S7** Sibling device survives Device A rotation | `RefreshRotationIT` | `refresh_rotation_preserves_sibling_in_same_family` | **PASS** *(new — closed in this pass)* |
| **O1** Valid bearer accepted (filter + boot) | `JwtAuthenticationFilterTest` + `OrderServiceBootIT` | `valid_token_populates_security_context_and_vendor_context` + `protected_endpoint_with_valid_token_passes_filter` | **PASS** |
| **O2** Expired bearer rejected | `order-service/.../JwtServiceTest` | `parse_throws_token_expired_when_exp_is_in_the_past` | **PASS at unit level.** Sibling BootIT tests cover tampered and unauthenticated paths. Order-service BootIT does not have a dedicated `expired_token` BootIT (advisory — add when filter slice grows). |
| **O3** Tampered bearer rejected | `JwtServiceTest` + `OrderServiceBootIT` | `parse_throws_token_invalid_when_signature_uses_different_secret` + `protected_endpoint_with_tampered_token_returns_401` | **PASS** |
| **X1** `JWT_SECRET` fail-fast (sync) | `JwtSecretValidatorTest` + `SecurityConfigIT` + `FullAppSmokeIT` | 3 unit cases; boot smoke positive case | **PARTIAL → PASS at unit level; boot smoke positive side covered.** Negative boot (missing `< 32`) proved at unit level only. |
| **X2** `JWT_SECRET` fail-fast (order) | `JwtSecretValidatorTest` + `ApplicationContextSmokeIT` | 3 unit + 1 boot | **PASS** (boot smoke present, unlike sync). |
| **X3** `vendor_id` informational only | `VendorContextTest` | 3 cases | **DEFERRED by spec design** — no authorization decision code path exists yet to assert the contract on. Recorded as future-work. |

**Coverage roll-up**: 9 PASS at IT level, dual coverage for 2 scenarios (slice + IT), 1 sibling-scenario gap closed this pass, 1 deferred-by-design.

---

## 4. Findings closed in this verify cycle

### 4.1 Spec↔design contradiction on S3 (AccountLocked) — RESOLVED

The original spec scenario "Locked Account Returns Account Locked Error" mandated HTTP 401 with body `{"error":"Account locked"}`. `design.md`, the implementation (`AuthExceptionHandler.onLocked` → `ResponseEntity.status(HttpStatus.LOCKED).body(…)`), and the IT (`AuthLoginIT.login_locked_account_returns_423_account_locked`) all used HTTP 423 with body `{"error":"account_locked"}`.

**Decision: align the spec to design + implementation.** HTTP 423 (Locked, RFC 4918 §11.3) is the more accurate status code for a locked resource; the `snake_case` body token matches the rest of the project's error convention. `specs/auth/spec.md` §"Login On A Locked Account Is Rejected Regardless Of Password Correctness" was updated to reflect 423 + `account_locked` body, with an inline source-of-truth note explaining the change. **No implementation change required.**

### 4.2 Test flake in `JwtServiceTest.parse_rejects_tampered_signature` — RESOLVED

The original assertion flipped the **last** character of the compact JWS. HS256 produces a 32-byte signature which base64url encodes to 43 characters; the last character contributes only 4 of 6 bits of useful data, with 2 trailing padding bits ignored. When the flip landed in those padding bits (~⅓ of cases), the decoded bytes were byte-identical, the HMAC still verified, and `parse()` returned successfully — the `assertThatThrownBy` then failed.

**Fix**: split the token on `.`, mutate `segments[2].charAt(sig.length() / 2)` to a different base64url character, and rejoin. A comment explains the rationale. Re-run: 4 / 4 PASS in `JwtServiceTest`.

### 4.3 Missing tests promised by `tasks.md` — RESOLVED

All four were added in this pass:

- **S-08** `AuthControllerWebMvcTest` (sync): 6 `@Test` covering `login_valid_returns_200_with_tokens_and_expires_in_900`, wrong password, locked account, missing email, invalid email format, and missing refresh_token field. Uses `@WebMvcTest(controllers = AuthController.class)` with `@Import({AuthExceptionHandler.class, SecurityConfig.class})` so the CSRF filter does not block POSTs with 403.
- **S-10 sync** `LogbackMaskingTest`: 3 `@Test` (masking positive, no-header passthrough, null-message → `""`).
- **S-10 sync** `SecurityConfigIT`: 3 `@Test` (health 200, protected endpoint without token → 401 `token_expired`, login permitAll + bad body → 400). `/actuator/info` test omitted because `management.endpoints.web.exposure.include` is not configured (would be either tautological or 404-dependent; not in spec scenarios X1/X2).
- **O-05** `LogbackMaskingTest` (order): mirror of the sync test against the order-side `BearerMaskingConverter`.

### 4.4 Scenario S7 (sibling-device rotation) — RESOLVED

`RefreshRotationIT.refresh_rotation_preserves_sibling_in_same_family` seeds user, logs in twice (device A and device B share a `token_family`), refreshes with device A's plaintext, and asserts: response 200, device A row `revoked=true`, **device B row still `revoked=false`**, new row minted in the same family.

### 4.5 Sync `*IT` classes never ran with `mvn verify` — RESOLVED

`sync-service/pom.xml` did not bind `maven-failsafe-plugin` (only `order-service/pom.xml` did). Symptom: `mvn -pl sync-service verify` reported `BUILD SUCCESS` with surefire output only; no `*IT.java` was ever executed. Fix: added explicit `<plugin>` bindings for both `maven-surefire-plugin` and `maven-failsafe-plugin` to `sync-service/pom.xml`, each with the same `<systemPropertyVariables>` injection for `jwt.secret` (rationale in pom comment). After the fix, `mvn -pl sync-service -am clean verify` runs all 14 ITs.

### 4.6 Toolchain preflight — RESOLVED

Project's `.tool-versions` now declares `java 21.0.2`. Future runs that fail to source it (or don't set `JAVA_HOME` explicitly) will surface a clear JDK mismatch instead of the cryptic Mockito-inline errors that the current run triggered. Recommend committing this in the same PR; the verification above confirms it works with both explicit `JAVA_HOME=…/mise/installs/java/21.0.2` and a `mise use java@21.0.2` workflow.

---

## 5. Per-task TDD evidence (strict TDD mode — RED, GREEN, TRIANGULATE, REFACTOR)

`apply-progress.md` does not exist on disk; this verify report reconstructs evidence from `git log`, commits, and the current test run.

| Task | Test(s) | RED observed? | GREEN observed today? | Verdict |
|---|---|---|---|---|
| S-01 deps | (no test) | n/a | build succeeds | OK |
| S-02 Flyway V1 | `FlywayMigrationsIT` (1) | bundled commit | PASS | OK |
| S-03 entities + repositories | `UserRepositoryContractTest`, `RefreshTokenRepositoryContractTest` | bundled | PASS | OK |
| S-04 fail-fast (sync) | `JwtSecretValidatorTest` (3) | bundled | PASS | OK |
| S-05 JwtService | `JwtServiceTest` (4) | bundled | **4/4 PASS** after the §4.2 fix | **OK** (was 3/4) |
| S-06 RefreshTokenCodec | `RefreshTokenCodecTest` (3) | bundled | PASS | OK |
| S-07 AuthService.login | `AuthLoginIT` (5) | bundled | PASS | OK |
| S-08 AuthController + handler | **`AuthControllerWebMvcTest` (6)** *(new)* | added this pass | PASS | **OK** (was MISSING) |
| S-09 refresh + burn + expiry | `RefreshRotationIT` (4) — sibling scenario added this pass | bundled | PASS | OK |
| S-10 SecurityConfig + masking (sync) | **`LogbackMaskingTest` (3)** + **`SecurityConfigIT` (3)** *(both new)* | added this pass | PASS | **OK** (was MISSING) |
| S-11 VendorContext stub | `VendorContextTest` style assertions in sync-side slice | n/a | OK | OK |
| S-12 application*.yml + AbstractPostgresIT | `FullAppSmokeIT`, `FlywayMigrationsIT`, `AuthLoginIT`, `RefreshRotationIT`, `SecurityConfigIT` | bundled | PASS | OK |
| S-13 Batch 1 verify gate | aggregate | OK | PASS | OK |
| O-01 deps + smoke | `ApplicationContextSmokeIT` | bundled | PASS | OK |
| O-02 fail-fast (order) | `JwtSecretValidatorTest` + `ApplicationContextSmokeIT` | bundled | PASS | OK |
| O-03 decoder-only JwtService + VendorContext | `JwtServiceTest` (4) + `VendorContextTest` (3) | bundled | PASS | OK |
| O-04 filter + SecurityConfig + handler | `JwtAuthenticationFilterTest` (4) | bundled | PASS | OK |
| O-05 application.yml + masking (order) | `OrderServiceBootIT` (4) + **`LogbackMaskingTest` (3)** *(new)* | added this pass | PASS | **OK** (was MASKING MISSING) |
| O-06 Batch 2 verify gate | aggregate | OK | PASS | OK |

No `CRITICAL` RED-missing items remain. Audit-trail weakness (single bundled commit per batch instead of RED-only then GREEN commits) is documented but not retriable now.

---

## 6. Deviations and advisories

1. **`AccountLockedException` → 423 / `account_locked`** is now the source of truth in spec + design + implementation. Documented in `specs/auth/spec.md` with an inline source-of-truth note (RFC 4918 §11.3 rationale). No code change required.
2. **`ddl-auto: create-drop`** in `order-service/src/test/resources/application-test.yml` instead of the Flyway migration that `design.md` §3.2 anticipates. Documented in commit `ad54f80`. Acceptable for this change; flag for a follow-up that brings pre-existing `Order` / `Product` / `OrderItem` under Flyway control.
3. **`JWT_SECRET` is injected as a `<systemPropertyVariables>` to surefire + failsafe** (both services' poms) because `JwtSecretEnvironmentPostProcessor` runs before `application*.yml`. Documented in the poms and in commit `ad54f80`. Acceptable; consider switching to `@TestPropertySource` if the env-processor approach becomes awkward.
4. **`AuthController.login` still has its own `try/catch` with `System.err.println("[DEBUG-CTRL] …")` debug logging.** `AuthExceptionHandler` already maps the same exceptions. Recommend a follow-up refactor that deletes the try/catch in the controller and lets the `@RestControllerAdvice` carry the wire contract, removing the debug println. **Recorded as out-of-scope for `auth-foundation`** — this is a refactor that touches no scenario or contract.
5. **`SecurityConfigIT` (sync) does not include an `/actuator/info` test** because `management.endpoints.web.exposure.include` is not configured for this slice. Acceptable.

---

## 7. Review workload verification (per `tasks.md` §"Review Workload Forecast")

| Forecast field | Tasks predicted | Observed | Drift |
|---|---|---|---|
| Estimated changed lines | 1,440 (1,065 / 375) | ~1,440 + ~250 of added tests / pom fixes in this verify cycle | tighter audit-trail but no scope creep outside the assigned batches |
| Chained PR shape | Batch 1 (sync) → Batch 2 (order) | Yes — `f0ee6a6` and `ad54f80` stack as designed | OK |
| Touched surface | sync + order only; no `docker-compose.yml` or `init-scripts/init.sql` | Confirmed; the only files modified in this verify pass are inside `sync-service/pom.xml`, `sync-service/src/test/`, `order-service/src/test/`, `openspec/changes/auth-foundation/specs/auth/spec.md`, and the project root `.tool-versions` | OK |

The 4R pre-PR review (`review-risk` + `review-reliability` minimum — auth + secret handling + masking; `review-readability` advisable because of the dev println in `AuthController`) still applies for the eventual `git push` / PR-open.

---

## 8. Verdict

**Status: PASS.** All scenarios in `specs/auth/spec.md` are covered. All strict-TDD-relevant tasks have tests that pass under JDK 21.0.2. The change is **ready to advance to `sdd-sync`**, which will promote `specs/auth/spec.md` (delta) into the canonical spec store once the user commits the changes and reviews the chained PRs.

Outstanding **non-blocking** follow-ups: the `AuthController` refactor (§6.4), the order-service Flyway migration (§6.2), and the `@TestPropertySource` rework for `JWT_SECRET` injection (§6.3).

---

## 9. skill_resolution

`skill_resolution: none` — no project/user skills were injected by the parent orchestrator for this phase. The sdd-verify agent carries its own runtime contract (per `~/.pi/agent/agents/sdd-verify.md`); the registry at `/home/hat/projects/hielo/.atl/skill-registry.md` did not surface any task-relevant path for verification.
