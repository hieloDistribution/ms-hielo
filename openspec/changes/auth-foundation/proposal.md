# SDD Proposal — `auth-foundation`

| Field | Value |
|---|---|
| Status | `proposed` |
| Change ID | `auth-foundation` |
| Working branch | `feature/auth-foundation` |
| Author | parent Pi session (sdd-proposal delegated) |
| Date | 2026-07-02 |
| Lifecycle | `init → explore → proposal` complete; awaiting approval to proceed to `spec` |

---

## 1. Intent

`auth-foundation` lays the prerequisite authentication layer for the Hielo
multi-service architecture before any PRD business endpoint is implemented.

- `sync-service` owns credentials and tokens: stores BCrypt-hashed passwords in
  `sync_db`, validates credentials on `POST /api/v1/auth/login`, issues
  short-lived access JWTs and rotating refresh tokens, and exposes
  `POST /api/v1/auth/refresh`.
- `order-service` validates JWTs locally using a shared HMAC-SHA256 secret
  (`JWT_SECRET` env var). It does **not** call `sync-service` for credential
  checks at runtime.
- Refresh tokens follow a single-use rotation policy with token-family theft
  detection: if a refresh token is replayed, the whole family is revoked
  atomically and the user must re-authenticate.
- This slice does **not** protect any PRD endpoint. That belongs to a
  follow-up change (`auth-protect-*`).

---

## 2. Scope

### IN

| Item | Detail |
|------|--------|
| `JWT_SECRET` provisioning | 256-bit HMAC-SHA256 key from `JWT_SECRET` env var. Both services fail-fast at startup if absent or shorter than 32 bytes. |
| Password storage | BCrypt, cost factor 12. Lower cost (4) in test profile only. |
| Login endpoint | `POST /api/v1/auth/login` on `sync-service`. Returns `{ access_token, refresh_token, expires_in }`. |
| Refresh endpoint | `POST /api/v1/auth/refresh` on `sync-service`. Rotates single-use refresh token; detects theft via family replay. |
| JWT claims | `sub` (user UUID), `vendor_id` (custom, informational only), `iat`, `exp`, `iss = "hielo-sync"`, `aud = "hielo-order"`. |
| Token TTLs | Access 15 min (`900` s); refresh 7 days. |
| Refresh token schema | `refresh_tokens` table in `sync_db` with `id`, `user_id`, `token_family`, `token_hash`, `expires_at`, `revoked`, `created_at`. |
| Local JWT validation | Nimbus JOSE+JWT on `order-service`. Validates signature, expiry, issuer, audience. |
| JWT filter chain | `OncePerRequestFilter` on both services. `order-service` exposes `vendor_id` via `VendorContext` (audit only). |
| DB migration | Flyway `V1__create_users_and_refresh_tokens.sql` inside `sync-service`. |
| Test plan | BDD/TDD, RED-first. Testcontainers PostgreSQL 15. See section 6. |
| Delivery strategy | Chained PR, two SDD changes: `auth-foundation-sync` then `auth-foundation-order`. Per `auto-forecast` because the total is ~1,440 lines, exceeding the 400-line budget. |

### OUT

| Item | Reason |
|------|--------|
| Protecting PRD endpoints (`@PreAuthorize`, method security) | Next change (`auth-protect-*`). |
| Logout / explicit token revocation | Deferred; refresh TTL bounds exposure. |
| Rate limiting on auth endpoints | Separate change. |
| MFA / TOTP | Deferred. |
| Password reset / forgot password | Separate change. |
| Redis or any cache backend | PostgreSQL only; `docker-compose.yml` unchanged. |
| `order-service` calling `sync-service` at runtime | Forbidden by design decision. |

---

## 3. Affected areas

### `sync-service`

| Path | Change |
|---|---|
| `pom.xml` | Add `spring-boot-starter-security`, `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, `org.testcontainers:postgresql` (test), `org.testcontainers:junit-jupiter` (test). |
| `src/main/resources/application.yml` | Add `jwt.*`, `spring.flyway.enabled`, datasource for `sync_db`. |
| `src/main/resources/db/migration/V1__create_users_and_refresh_tokens.sql` | New. Creates `users` and `refresh_tokens` with `gen_random_uuid()` defaults. |
| `src/main/java/com/sales/auth/model/User.java` | JPA entity for `users`. |
| `src/main/java/com/sales/auth/model/RefreshToken.java` | JPA entity for `refresh_tokens`. |
| `src/main/java/com/sales/auth/repository/UserRepository.java` | `findByEmail`. |
| `src/main/java/com/sales/auth/repository/RefreshTokenRepository.java` | `findByTokenHashAndRevokedFalse`, `revokeFamily`. |
| `src/main/java/com/sales/auth/dto/LoginRequest.java` | `{email, password}` with Bean Validation. |
| `src/main/java/com/sales/auth/dto/AuthResponse.java` | `{access_token, refresh_token, expires_in}`. |
| `src/main/java/com/sales/auth/dto/RefreshRequest.java` | `{refresh_token}`. |
| `src/main/java/com/sales/auth/service/AuthService.java` | Login, refresh, theft detection. |
| `src/main/java/com/sales/auth/service/JwtService.java` | Sign and verify HS256 with Nimbus. |
| `src/main/java/com/sales/auth/controller/AuthController.java` | Two endpoints. |
| `src/main/java/com/sales/auth/security/SecurityConfig.java` | Stateless, `permitAll` on `/api/v1/auth/**`, denies everything else, fails fast without `JWT_SECRET`. |
| `src/main/java/com/sales/auth/security/JwtAuthenticationFilter.java` | Reads `Authorization: Bearer`, validates, sets `SecurityContext`. |
| `src/test/java/com/sales/auth/...` | BDD integration tests for login, locked account, refresh, theft detection, multi-device. |

### `order-service`

| Path | Change |
|---|---|
| `pom.xml` | Add `spring-boot-starter-security`, `org.testcontainers:postgresql` (only if local BDD test needs it; otherwise not). |
| `src/main/resources/application.yml` | Add `jwt.*` config. No datasource for `order_db` in this change (kept as future work). |
| `src/main/java/com/sales/auth/security/SecurityConfig.java` | Stateless, requires authentication on all paths except actuator health. |
| `src/main/java/com/sales/auth/security/JwtAuthenticationFilter.java` | Verifies JWT locally with Nimbus using `JWT_SECRET`. |
| `src/main/java/com/sales/auth/dto/VendorContext.java` | Reads `vendor_id` claim. Javadoc: never use as the sole authorisation gate. |
| `src/test/java/com/sales/auth/security/...` | Unit + integration tests for valid/expired/tampered JWT, `vendor_id` extraction. |

### Shared / infrastructure

- `docker-compose.yml` — no change. (No Redis, no new containers.)
- `init-scripts/init.sql` — no change.
- Environment: `JWT_SECRET` shared between both service containers. Both
  services boot-fail when missing or shorter than 32 bytes.

---

## 4. Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│                       sync-service                            │
│                                                               │
│  POST /api/v1/auth/login                                      │
│    → AuthService.login(email, plain)                          │
│    → BCrypt.matches(users.password_hash, plain)               │
│    → JwtService.sign(sub=userId, vendor_id,                   │
│                      iss="hielo-sync",                        │
│                      aud="hielo-order",                       │
│                      ttl=15min)                               │
│    → generate new token_family (UUID)                         │
│    → persist RefreshToken(row, revoked=false)                 │
│    ← { access_token, refresh_token, expires_in:900 }           │
│                                                               │
│  POST /api/v1/auth/refresh { refresh_token }                   │
│    → hash token (SHA-256), find row revoked=false             │
│    → IF row missing or expired: 401                           │
│    → ELSE IF row.token_family already saw a previous revoke:  │
│         revokeFamily(family) → 401  (theft detected)          │
│    → ELSE:                                                     │
│         mark old row revoked=true                              │
│         insert new row in same family (revoked=false)          │
│         issue new access_token                                 │
│         ← { access_token, refresh_token, expires_in:900 }    │
└──────────────────────────────────────────────────────────────┘
                            │
                       no HTTP call
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│                      order-service                            │
│                                                               │
│  JwtAuthenticationFilter (OncePerRequestFilter)               │
│    → Authorization: Bearer <jwt>                              │
│    → Nimbus: verify HS256 signature with JWT_SECRET           │
│    → verify exp, iss, aud                                     │
│    → extract vendor_id → VendorContext (audit only)           │
│    → set SecurityContext OR 401                               │
│                                                               │
│  NOTE: vendor_id is informational. Real authorisation         │
│  must re-query the data source. Never gate on claim alone.    │
└──────────────────────────────────────────────────────────────┘
```

**Credential ownership.** `sync-service` owns the `users` table. `order-service`
never validates passwords and never queries `sync_db` for credentials.

**Refresh token family model.** Each successful login issues a fresh
`token_family` UUID. Every refresh writes a new row in the same family and
marks the previous row `revoked=true`. Replay of any revoked row revokes the
entire family in one SQL UPDATE. This bounds the blast radius of a stolen
refresh token to the family (≈ one session).

**HMAC secret distribution.** `JWT_SECRET` comes from the container env and is
identical in both pods. Rotation is out of scope (next change).

---

## 5. Test plan (BDD / TDD, RED-first)

All tests follow RED → GREEN → TRIANGULATE → REFACTOR. Integration tests use
**Testcontainers PostgreSQL 15**; unit tests mock dependencies.

### `sync-service` scenarios

| # | Scenario | Given | When | Then | Test class | First cycle |
|---|---|---|---|---|---|---|
| 1 | Login happy path | user with email `alice@example.com` and BCrypt password | `POST /api/v1/auth/login` with correct password | `200` with `access_token`, `refresh_token`, `expires_in=900`; `refresh_tokens` row inserted with `revoked=false` | `AuthControllerIT` | RED |
| 2 | Login wrong password | user exists | `POST /api/v1/auth/login` with wrong password | `401`, no refresh row | `AuthControllerIT` | RED |
| 3 | Login locked account | user with `locked=true` | login with correct password | `401`, body `{"error":"Account locked"}` | `AuthServiceTest` (unit) | RED |
| 4 | Refresh happy path | valid, unrevoked refresh row | `POST /api/v1/auth/refresh` | `200`, new tokens, old row `revoked=true`, new row same family `revoked=false` | `RefreshTokenIT` | RED |
| 5 | Refresh replay (theft detection) | valid `RT1` in family `F1` | submit `RT1` twice | first call rotates; second call `401`, every row in `F1` is `revoked=true` | `RefreshTokenTheftDetectionIT` | RED |
| 6 | Refresh expired | row `expires_at < now()`, `revoked=false` | `POST /api/v1/auth/refresh` | `401` | `RefreshTokenIT` | RED |
| 10 | Multi-device rotation | `RT-A` on Device A and `RT-B` on Device B, both same family | Device A refreshes with `RT-A` | new tokens for A; `RT-B` is still valid | `RefreshTokenMultiDeviceIT` | RED |

### `order-service` scenarios

| # | Scenario | Given | When | Then | Test class | First cycle |
|---|---|---|---|---|---|---|
| 7 | JWT validation valid | valid JWT signed with `JWT_SECRET`, correct `iss/aud`, future `exp` | request with `Authorization: Bearer <jwt>` | request authorised, `SecurityContext` populated, `VendorContext.vendorId()` returns expected UUID | `OrderServiceJwtFilterIT` | RED |
| 8 | JWT validation expired | JWT with `exp` in the past, valid signature | request | `401`, empty `SecurityContext` | `OrderServiceJwtFilterIT` | RED |
| 9 | JWT tampered signature | JWT signed with a different secret | request | `401` | `OrderServiceJwtFilterTest` (unit) | RED |

### Cross-cutting (both services)

| # | Scenario | Verification |
|---|---|---|
| 11 | Fail-fast on missing `JWT_SECRET` | `@SpringBootTest` without `JWT_SECRET` env throws on context start. |
| 12 | `vendor_id` audit-only contract | `VendorContextTest` asserts behaviour and the javadoc warning. |

The 10 numbered scenarios from the explore round are preserved; scenario 10
(`multi-device rotation`) is folded into the sync-service set.

---

## 6. Confirmed decisions (locked by user answers)

| # | Decision | Answer |
|---|---|---|
| Q1 | Credential store | `sync_db`, `sync-service` owns auth. |
| Q2 | Token validation topology | Local HMAC on `order-service` (shared secret). |
| Q3 | Refresh persistence | PostgreSQL only (`refresh_tokens` in `sync_db`). |
| Q4 | Rotation policy | Single-use + token family theft detection. |
| Q5 (default) | JWT claims scope | `vendor_id` informational/audit only on `order-service`. |
| Q6 | Workload split | Chained PR, two SDD changes (`auth-foundation-sync`, `auth-foundation-order`). |
| Q7 | Test infrastructure | Testcontainers with PostgreSQL 15. |

---

## 7. Open assumptions (override before spec)

| # | Assumption | Default | Why |
|---|---|---|---|
| A1 | BCrypt cost factor in prod | 12 | OWASP 2023 minimum. |
| A2 | BCrypt cost factor in tests | 4 (via `@TestPropertySource`) | Keeps integration tests fast. |
| A3 | Access token TTL | 15 min (`900` s) | Bounds access-token replay window. |
| A4 | Refresh token TTL | 7 days | Balances UX vs revocation lag. |
| A5 | JWT signature algorithm | HS256 (HMAC-SHA256) | Symmetric; shared secret. |
| A6 | `JWT_SECRET` minimum length | 32 bytes / 256 bits | Fails fast at startup. |
| A7 | `vendor_id` claim scope | Informational/audit only | Never the sole authorisation gate. |
| A8 | Token family ID | UUID v4 per new login | Allows family revocation without re-issuing everything. |

---

## 8. Risks

| ID | L | I | Risk | Mitigation |
|---|---||---|---|---|
| R1 | L | Critical | `JWT_SECRET` leaked via source or logs | Mandatory env, fail-fast on < 32 bytes, logback token masking. |
| R2 | M | M | `refresh_tokens` grows unbounded | Cleanup job deferred to `auth-cleanup` change. |
| R3 | M | High | `vendor_id` claim used as sole auth gate on `order-service` | Javadoc + explicit warning in `VendorContext`. |
| R4 | M | Low | BCrypt 12 too slow in integration tests | `@TestPropertySource` with cost=4 in `test` profile. |
| R5 | L | M | Flyway `V1` migration conflicts | `auth-foundation-sync` owns `V1` in `sync-service`. |
| R6 | L | M | Clock skew between services | UTC everywhere; ±60 s leeway configurable. |
| R7 | VL | High | DB mid-rotation failure leaves a half-rotated state | `@Transactional` on rotation; rollback on exception. |
| R8 | M | M | `JWT_SECRET` rotation invalidates sessions | TTL bounds impact. Key versioning in follow-up. |
| R9 | M | M | No rate limit on `/api/v1/auth/login` | Deferred. Lockout-by-N-failures belongs to `auth-protect-*`. |
| R10 | L | M | Version conflict between `spring-boot-starter-security` and Spring Boot 3.2.5 | Use the Spring Boot managed BOM; no explicit version. |
| R11 | M | M | Two chained PRs increase coordination cost | Each PR is independently mergeable; second PR is additive, not modifying. |

---

## 9. Rollback

No production data yet; rollback is straightforward.

1. Revert code: delete `src/main/java/com/sales/auth/`, `src/test/java/com/sales/auth/`, the Flyway migration; restore both `pom.xml`s.
2. Revert DB: `DROP TABLE IF EXISTS refresh_tokens; DROP TABLE IF EXISTS users;` on `sync_db`.
3. Remove `jwt.*` keys from each `application.yml`.
4. No downstream consumers; no production data affected.

---

## 10. Success criteria

| ID | Criterion | Verification |
|---|---|---|
| SC1 | `mvn -pl sync-service test` green | `mvn -pl sync-service test` |
| SC2 | `mvn -pl order-service test` green | `mvn -pl order-service test` |
| SC3 | `mvn -pl sync-service verify` green | `mvn -pl sync-service verify` |
| SC4 | `mvn -pl order-service verify` green | `mvn -pl order-service verify` |
| SC5 | Login happy path 200 with both tokens | `AuthControllerIT` scenario 1 |
| SC6 | Login wrong password 401 | scenario 2 |
| SC7 | Refresh rotates and marks old as revoked | scenario 4 |
| SC8 | Refresh replay revokes whole family | scenario 5 |
| SC9 | Valid JWT on `order-service` 200 | scenario 7 |
| SC10 | Expired JWT on `order-service` 401 | scenario 8 |
| SC11 | Tampered JWT on `order-service` 401 | scenario 9 |
| SC12 | Both services boot-fail without `JWT_SECRET` | scenario 11 |
| SC13 | `VendorContext` exposes `vendor_id` and warns about scope | scenario 12 |

---

## 11. Out-of-scope reminders

| Item | Future change |
|---|---|
| Protecting PRD endpoints | `auth-protect-*` |
| Logout / explicit revocation | `auth-logout` |
| Rate limiting on auth endpoints | `auth-rate-limit` |
| MFA / TOTP | `auth-mfa` |
| Password reset | `auth-password-reset` |
| Redis-backed refresh tokens | `auth-redis` |
| Zero-downtime `JWT_SECRET` rotation | `auth-key-rotation` |
| Expired refresh cleanup job | `auth-cleanup` |
| Brute-force lockout after N failed attempts | `auth-protect-*` |

---

## 12. Workload forecast

Estimated 1,440 changed lines, 29 files. Exceeds the 400-line budget, so
`auto-forecast` produces a chained PR strategy with two SDD changes:

| Change | Files | Lines |
|---|---|---|
| `auth-foundation-sync` | sync-service pom + migration + 12 src + 6 test | ≈ 1,065 |
| `auth-foundation-order` | order-service pom + 4 src + 3 test | ≈ 375 |

Each change lands its own PR. The second PR is additive and does not modify
the first PR's footprint.

---

## 13. Proposal question round (closed)

Five product questions were answered by the user before this artifact was
written. They are recorded in section 6. The original "Proposal Question
Round" block from the proposal draft was removed because its answers are now
locked.

---

## 14. Next recommended

Parent should:

1. Persist this file at `openspec/changes/auth-foundation/proposal.md` (done
   by the parent).
2. Ask the user to **approve** the proposal or note overrides for the open
   assumptions (A1–A8) before `sdd-spec` runs.
3. After approval, launch `sdd-spec` for the `auth-foundation` change (delta
   spec with the 10 scenarios plus cross-cutting scenarios).
4. Then `sdd-design` (technical design: package layout, JSON contracts, error
   model, security filter wiring).
5. Then `sdd-tasks` with the chained-PR plan and RED-first cycles.
6. Then `sdd-apply` per batch, with strict TDD evidence.

---

## skill_resolution

`skill_resolution: none` — no project/user skill paths injected.
