# Tasks — `auth-foundation`

| Field | Value |
|---|---|
| Status | `tasks` |
| Change | `auth-foundation` (covers `auth-foundation-sync` then `auth-foundation-order`) |
| Working branch | `feature/auth-foundation` |
| Strict TDD | ON — every task lists a RED step that must be observed failing before its GREEN |

This change is delivered as **two chained SDD changes** (two PRs). Each PR
is self-contained: Batch 1 lands, then Batch 2 stacks on top.

Tasks are listed RED-first within each batch. Every RED must be observed as
a failing test (or as a hard compiler / Spring context failure) before the
GREEN task produces the code that satisfies it.

Do NOT modify `docker-compose.yml` or `init-scripts/init.sql` in either batch.

---

## Review Workload Forecast

| Field | Value |
|---|---|
| Estimated changed lines | 1,440 (Batch 1 ≈ 1,065; Batch 2 ≈ 375) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1: `auth-foundation-sync` → PR 2: `auth-foundation-order` |
| Delivery strategy | auto-chain |
| Chain strategy | feature-branch-chain |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: feature-branch-chain
400-line budget risk: High

---

## Scenario → task coverage matrix

| Spec scenario | Batch | Task id |
|---|---|---|
| S1: Login happy path | Batch 1 | S-07 |
| S2: Login wrong password | Batch 1 | S-07 |
| S3: Login locked account | Batch 1 | S-07 |
| S4: Refresh happy path | Batch 1 | S-09 |
| S5: Refresh replay revokes family | Batch 1 | S-09, S-09 |
| S6: Refresh expired | Batch 1 | S-09 |
| S7: Multi-device refresh in same family survives | Batch 1 | S-07 (family join) |
| O1: Valid JWT accepted on order-service | Batch 2 | O-04 |
| O2: Expired JWT rejected on order-service | Batch 2 | O-04 |
| O3: Tampered JWT rejected on order-service | Batch 2 | O-04 |
| X1: Fail-fast without JWT_SECRET (sync) | Batch 1 | S-04 |
| X2: Fail-fast without JWT_SECRET (order) | Batch 2 | O-02 |
| X3: vendor_id informational only | Batch 1 | S-08; Batch 2 | O-03 |

---

## Batch 1 — `auth-foundation-sync`

Source of the chain. ~1,065 lines. Closes scenarios S1–S7 plus sync-service
halves of X1 and X3.

### S-01 — Add security, Flyway and Testcontainers dependencies
Add to `sync-service/pom.xml`:
- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server`
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `org.testcontainers:postgresql` (test scope)
- `org.testcontainers:junit-jupiter` (test scope)
- `com.nimbusds:nimbus-jose-jwt` is transitively present; do NOT add explicitly.
TDD step: RED baseline.
Verify: `mvn -pl sync-service compile` succeeds; `mvn -pl sync-service test`
still passes with the empty test set.
Out of scope: do not modify `order-service/pom.xml` here.

### S-02 — Flyway V1 migration + migration present
New: `sync-service/src/main/resources/db/migration/V1__create_users_and_refresh_tokens.sql`
using the DDL from design §4 (`pgcrypto` extension, `users`, `refresh_tokens`,
indexes).
TDD step: RED.
Verify: a new `FlywayMigrationsIT` extending `AbstractPostgresIT`
(introduced in S-12; for now inline `Testcontainers` in this single test)
asserts that after context start:
- `users` exists with columns `id`, `email`, `password_hash`, `vendor_id`,
  `locked`, `created_at`, `updated_at`.
- `refresh_tokens` exists with columns `id`, `user_id`, `token_family`,
  `token_hash`, `expires_at`, `revoked`, `created_at`.
- Indexes `ix_refresh_tokens_user_id`, `ix_refresh_tokens_family`,
  `ix_refresh_tokens_revoked_exp` are present.
GREEN: create the `V1__*.sql` file with the exact DDL from design §4.
Run `mvn -pl sync-service verify` and watch the RED go GREEN.
Out of scope: no schema data; no other Flyway files.

### S-03 — JPA entities and repositories
New Java files inside `sync-service/src/main/java/com/sales/sync/auth/`:
- `model/User.java` — `@Entity users`.
- `model/RefreshToken.java` — `@Entity refresh_tokens`.
- `repository/UserRepository.java` — `findByEmail(String)`.
- `repository/RefreshTokenRepository.java` — `findByTokenHash(String)`,
  `findActiveFamilyByUserId(UUID)`, `burnFamily(UUID)`.
TDD step: RED via `UserRepositoryContractTest` and
`RefreshTokenRepositoryContractTest` (`@DataJpaTest` slice, Testcontainers).
Each test exercises one query. They fail with `NoSuchBeanDefinitionException`
or `InvalidDataAccessApiUsageException` (no entity) before the GREEN step.
GREEN: write entities and repositories.
Verify: `mvn -pl sync-service test` passes the contract tests.

### S-04 — JWT_SECRET fail-fast validator + environment post-processor
New:
- `sync-service/src/main/java/com/sales/sync/auth/security/JwtSecretValidator.java`
  — `@Component` with `@PostConstruct` that throws `IllegalStateException`
  if secret absent or shorter than 32 bytes.
- `sync-service/src/main/java/com/sales/sync/auth/security/JwtSecretEnvironmentPostProcessor.java`
  — implements `EnvironmentPostProcessor` with the same check.
- `sync-service/src/main/resources/META-INF/spring.factories`
  — registers the post-processor.
TDD step: RED.
Verify: `JwtSecretValidatorTest` (unit) starts a small
`AnnotationConfigApplicationContext` without `jwt.secret` and asserts the
exception. Same with a 16-byte secret. Then full RED-to-GREEN with the
@Component present. `JwtSecretValidatorIT` (`@SpringBootTest`) launches with
`JWT_SECRET` < 32 bytes → expects `ApplicationContextException`.
GREEN: implement bean + post-processor + `spring.factories`.
Out of scope: do not yet wire the validator into `JwtService`; that is S-05.

### S-05 — JwtProperties + JwtService (sign + parse)
New:
- `sync-service/src/main/java/com/sales/sync/auth/security/JwtProperties.java`
  — `@ConfigurationProperties(prefix = "jwt")` record matching design §5.
- `sync-service/src/main/java/com/sales/sync/auth/security/JwtService.java`
  — HS256 sign and parse using Nimbus. Method shapes:
  - `String sign(UUID userId, UUID vendorId)`.
  - `JWTClaimsSet parse(String token)` (throws `TokenExpiredException`,
    `TokenRevokedException`, or `TokenInvalidException`).
TDD step: RED via `JwtServiceTest` (unit, no Spring context required) with
these cases:
- Sign → parse round-trip recovers `sub`, `vendor_id`, `iss`, `aud`, `iat`,
  `exp`.
- Tampered signature → `TokenInvalidException`.
- Wrong `iss` → `TokenRevokedException`.
- Past `exp` → `TokenExpiredException`.
GREEN: implement the service. Tests move to GREEN.
Out of scope: `RefreshTokenCodec` is S-06, not here.

### S-06 — RefreshTokenCodec (UUID v4 + SHA-256)
New:
- `sync-service/src/main/java/com/sales/sync/auth/security/RefreshTokenCodec.java`
  — `generate()` returns an `OpaqueRefreshToken` (record with `plaintext`,
  `hash`). Hash is `sha256Hex(plaintext)`.
TDD step: RED via `RefreshTokenCodecTest` (unit):
- Two calls to `generate()` produce different `plaintext`.
- `sha256Hex(plaintext)` matches `hash`.
- `hash` is exactly 64 hex chars.
GREEN: implement.

### S-07 — AuthService.login (happy + wrong + locked + family join/creation)
New:
- `sync-service/src/main/java/com/sales/sync/auth/service/AuthService.java`
  — orchestrates `UserRepository` + `BCryptPasswordEncoder` + `JwtService`
  + `RefreshTokenCodec` + `RefreshTokenRepository.findActiveFamilyByUserId`
  per design §7.
TDD step: RED via `AuthLoginIT` (Testcontainers, `@SpringBootTest`).
Cases (each is its own `@Test`):
- Login happy path → `AuthResponse` populated, one new refresh row in
  `revoked=false`, family minted (Scenario 1 + First-Login-Family).
- Wrong password → `InvalidCredentialsException` (Scenario 2).
- Locked user → `AccountLockedException` (Scenario 3).
- Subsequent login joins existing active family (Family-Join scenario).
GREEN: implement `AuthService.login`.
Verify: `mvn -pl sync-service verify` — all four cases GREEN.

### S-08 — AuthController (login) + DTOs + ErrorCode + AuthExceptionHandler
New:
- `sync-service/src/main/java/com/sales/sync/auth/dto/{LoginRequest,AuthResponse,ErrorResponse}.java`
  matching design §14.
- `sync-service/src/main/java/com/sales/sync/auth/support/ErrorCode.java`
  enum with the body values from the mapping table.
- `sync-service/src/main/java/com/sales/sync/auth/support/AuthExceptionHandler.java`
  — `@RestControllerAdvice` scoped to `com.sales.sync.auth.controller`.
- `sync-service/src/main/java/com/sales/sync/auth/controller/AuthController.java`
  — `POST /api/v1/auth/login`.
TDD step: RED via `AuthControllerWebMvcTest` (`@WebMvcTest` slice) —
- Valid login returns 200 with body matching `AuthResponse`.
- Missing `email` → 400 `invalid_request`.
- Invalid `email` format → 400.
- Wrong password → 401 `invalid_credentials`.
- Locked user → 423 `account_locked`.
GREEN: implement controller and handler.
Verify: `mvn -pl sync-service test` passes the slice tests.

### S-09 — RefreshRotationService + refresh controller + family burn + expiry
New:
- `sync-service/src/main/java/com/sales/sync/auth/service/RefreshRotationService.java`
  — `@Transactional` `rotate(String plaintext)` per design §6.
- `sync-service/src/main/java/com/sales/sync/auth/controller/AuthController.java`
  — add `POST /api/v1/auth/refresh`.
- `sync-service/src/main/java/com/sales/sync/auth/dto/RefreshRequest.java`.
TDD step: RED via three ITs:
- `RefreshRotationIT` — happy refresh (Scenario 4).
- `RefreshRotationIT` — replay of already-revoked refresh (Scenario 5) —
  response 401, **all** rows in same `token_family` now `revoked=true`.
- `RefreshFamilyBurnIT` — after burn, sibling device's row also rejected
  (Scenario 7).
- `ExpiredTokenIT` — `expires_at < now()` returns 401 and does **not** burn
  the family (Scenario 6).
GREEN: implement the rotation service and endpoint.
Verify: `mvn -pl sync-service verify`.

### S-10 — SecurityConfig sync-service + CORS + logback masking
New:
- `sync-service/src/main/java/com/sales/sync/auth/security/SecurityConfig.java`
  — filter chain per design §8.1.
- `sync-service/src/main/java/com/sales/sync/auth/security/CorsConfig.java`
  with `CorsConfigurationSource` bean per design §12.
- `sync-service/src/main/resources/logback-spring.xml` with
  `<conversionRule>` for masking.
- `sync-service/src/main/java/com/sales/sync/auth/support/BearerMaskingConverter.java`
  per design §13.
TDD step: RED.
- `SecurityConfigIT`: `@SpringBootTest(webEnvironment = RANDOM_PORT)` boots
  the slice and asserts:
  - `GET /actuator/health` → 200.
  - `POST /api/v1/auth/refresh` without token → 401 (permitAll path, but
    fails because token is missing) or 401 token_revoked depending on
    shape; verify the JSON body.
  - `GET /api/v1/anything-else` without token → 401 `token_expired` /
    `token_revoked`.
- `LogbackMaskingTest` (unit) feeds a pattern string with a fake
  `Authorization: Bearer eyJ...` and asserts the token is replaced with
  `***`.
GREEN: implement.
Verify: `mvn -pl sync-service verify`.

### S-11 — VendorContext stub in sync-service + cross-cutting checks
New:
- `sync-service/src/main/java/com/sales/sync/auth/security/VendorContext.java`
  — `@RequestScope` bean with `Optional<UUID> vendorId`.
TDD step: RED via `VendorContextTest` (unit, manual request scope setup).
GREEN: implement.
Cross-cutting: this task is small; it is here so the audit-only annotation
is present in both services.

### S-12 — application.yml + application-test.yml + AbstractPostgresIT
New:
- `sync-service/src/main/resources/application.yml` per design §3.1.
- `sync-service/src/test/resources/application-test.yml` per design §11.
  `jwt.secret` is exactly 41+ bytes; `security.bcrypt-cost=4`.
- `sync-service/src/test/java/com/sales/sync/auth/it/AbstractPostgresIT.java`
  per design §10. Other `*IT` classes introduced in S-02, S-07, S-09, S-10
  must extend this base. Refactor them as part of the GREEN.
TDD step: RED — a new `FullAppSmokeIT` extending `AbstractPostgresIT`
launches the full Spring Boot app and asserts
`GET /actuator/health` → 200 with body `{"status":"UP"}`. RED before
`AbstractPostgresIT` exists: `ClassNotFoundException`. RED after
`AbstractPostgresIT` exists but `application-test.yml` missing: context
boot fails. GREEN: complete both files.
Verify: `mvn -pl sync-service verify`.

### S-13 — Verify Batch 1 end-to-end
Single gate for Batch 1. Run:
```bash
mvn -pl sync-service clean verify
```
Expected: `BUILD SUCCESS`, all `*Test` and `*IT` GREEN. Specifically:
- All `@DataJpaTest` repository contracts pass.
- All login scenarios (S-07) GREEN.
- All refresh scenarios (S-09) GREEN, family-burn verified.
- `SecurityConfigIT` and `LogbackMaskingTest` GREEN.
- Full app boots and `/actuator/health` returns UP.
No code change in this task — it is a verification gate.

---

## Batch 2 — `auth-foundation-order`

Source of the chain, stacked on top of `auth-foundation-sync`. ~375 lines.
Closes O1–O3 plus the order-service halves of X1 and X3.

### O-01 — Add security + OAuth2 resource server deps to order-service
Modify `order-service/pom.xml`:
- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server`
Do NOT add datasource, Flyway, or Testcontainers in this batch (no DB usage
yet in `order-service` for the auth slice).
TDD step: RED baseline.
New test: `order-service/src/test/java/com/sales/order/ApplicationContextSmokeIT.java`
— a `@SpringBootTest` (no DB) that asserts the context loads with a stub
`JWT_SECRET`. RED: no `spring.factories` for the JWT validator yet, so
context fails with the validator still hitting the env check; once O-02 is
GREEN, the smoke test goes GREEN.
Verify: `mvn -pl order-service test` passes the smoke test.

### O-02 — JWT_SECRET fail-fast in order-service
Mirror S-04 in `order-service`. New:
- `order-service/src/main/java/com/sales/order/auth/security/JwtSecretValidator.java`
- `order-service/src/main/java/com/sales/order/auth/security/JwtSecretEnvironmentPostProcessor.java`
- `order-service/src/main/resources/META-INF/spring.factories`
TDD step: RED via `JwtSecretValidatorTest` (unit) and a boot test in
`ApplicationContextSmokeIT` that runs with `JWT_SECRET=<16-byte-string>`
and expects `ApplicationContextException`.
GREEN: implement.
Out of scope: do NOT reuse the sync-service classes; copy-paste by design.

### O-03 — JwtService decoder-only + VendorContext (request-scoped) in order-service
New:
- `order-service/src/main/java/com/sales/order/auth/security/JwtService.java`
  — parse-only Nimbus wrapper (no `sign`). Same `parse(String)` shape so the
  filter can depend on a uniform interface.
- `order-service/src/main/java/com/sales/order/auth/security/VendorContext.java`
  — `@RequestScope`, `Optional<UUID> vendorId`.
TDD step: RED.
- `JwtServiceTest`: parse a token issued by `sync-service` (the test can
  use a fixed secret and `JWSObject` directly to construct one) and
  recover `sub`, `vendor_id`, `iss`, `aud`, `iat`, `exp`.
- `VendorContextTest`: set/clear around a `MockHttpServletRequest`; assert
  `get()` returns the right `Optional`.
GREEN: implement.

### O-04 — JwtAuthenticationFilter + SecurityConfig (order) + AuthExceptionHandler
New:
- `order-service/src/main/java/com/sales/order/auth/security/JwtAuthenticationFilter.java`
  — `OncePerRequestFilter` per design §8.2 / filter pseudocode.
- `order-service/src/main/java/com/sales/order/auth/security/SecurityConfig.java`
  — stateless, requires auth on everything except actuator health.
- `order-service/src/main/java/com/sales/order/auth/support/AuthExceptionHandler.java`
  — `@RestControllerAdvice` mapping `TokenExpired` / `TokenRevoked` /
  `TokenInvalid` to JSON.
TDD step: RED via `JwtAuthenticationFilterTest` (slice, no DB):
- No `Authorization` header → filter is a no-op; protected endpoint still
  returns 401.
- Bearer with valid token → `SecurityContext` populated with the userId
  principal; `VendorContext.vendorId()` returns the expected UUID
  (Scenario O1).
- Bearer with past `exp` → 401 `token_expired` (Scenario O2).
- Bearer with tampered signature → 401 `token_revoked` (Scenario O3).
GREEN: implement.

### O-05 — order-service application.yml + logback-spring.xml + BearerMaskingConverter
New:
- `order-service/src/main/resources/application.yml` per design §3.2.
- `order-service/src/main/resources/logback-spring.xml` with masking.
- `order-service/src/main/java/com/sales/order/auth/support/BearerMaskingConverter.java`.
TDD step: RED.
- `OrderServiceBootIT`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`,
  no DB. Asserts:
  - `GET /actuator/health` → 200 UP.
  - `GET /api/v1/whatever` without token → 401 `token_expired` /
    `token_revoked`.
  - When the test manually sets a valid `Authorization` header (built with
    the same `JwtService`-equivalent, since `order-service` does not own a
    signer in this slice, the test uses a small inline HS256 helper), the
    protected path passes the filter and reaches the controller (which
    returns its own 404; **what matters is that the response is not
    401**).
- `LogbackMaskingTest` (unit) — same shape as sync-service, fed with
  order-service logback config.
GREEN: implement.

### O-06 — Verify Batch 2 end-to-end
Single gate for Batch 2. Run:
```bash
mvn -pl order-service clean verify
```
Expected: `BUILD SUCCESS`, all `*Test` and `*IT` GREEN.

---

## Cross-cutting verification (both batches)

```bash
# Batch 1
mvn -pl sync-service clean verify

# Batch 2
mvn -pl order-service clean verify
```

A combined smoke (recommended before tagging either PR):

```bash
# Bring up postgres locally (or rely on docker-compose)
docker compose up -d postgres

# Start sync-service with a real JWT_SECRET, mint a token via curl,
# call a stub endpoint on order-service with that token.
JWT_SECRET="$(openssl rand -base64 48)" ./mvnw -pl sync-service spring-boot:run &
JWT_SECRET=$JWT_SECRET ./mvnw -pl order-service spring-boot:run &

# After both come up:
# 1) POST /api/v1/auth/login on sync-service → tokens.
# 2) GET /api/v1/orders/{id} on order-service with the access token → 200 (or
#    404 if the order doesn't exist — what matters is the auth passed).
# 3) Replay the refresh token twice on sync-service → first 200, second 401.
```

This smoke is advisory for the reviewer; it is not a substitute for the
`mvn verify` automation.

---

## Out-of-scope reminders (do not touch in either batch)

- `docker-compose.yml` — no Redis or new containers.
- `init-scripts/init.sql` — no change.
- `order-service` datasource / schema (owned by `core-orders`).
- `core-orders` business endpoints.
- Logout endpoint.
- Rate limiting on auth endpoints.
- MFA / password reset.
- Token blacklist table; logout is rotate-and-revoke current family.

---

## skill_resolution

`skill_resolution: none` — no project/user skill paths injected.
