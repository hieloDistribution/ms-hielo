# Design — `auth-foundation`

| Field | Value |
|---|---|
| Status | `design` |
| Change | `auth-foundation` (covers `auth-foundation-sync` then `auth-foundation-order`) |
| Stack | Java 21, Spring Boot 3.2.5, Maven, Package-by-Layers, PostgreSQL 15 via Testcontainers |
| Working branch | `feature/auth-foundation` |

---

## 1. Architecture overview

Two services with a shared JWT trust contract. The refresh store lives in
`sync-service`; `order-service` is read-only with respect to refresh state.

```text
   Browser / API client
        │
        │  POST /api/v1/auth/login
        │  POST /api/v1/auth/refresh
        ▼
 ┌──────────────────────────────────────────────────────┐
 │                     sync-service                       │
 │  AuthController → AuthService                          │
 │                 → RefreshRotationService (Tx)          │
 │                 → JwtService (HS256 sign)              │
 │                 → RefreshTokenCodec                    │
 │  JwtAuthenticationFilter                               │
 │  UserRepository / RefreshTokenRepository               │
 │  BCryptPasswordEncoder (cost 12 prod / 4 test)         │
 │                 │                                      │
 │                 ▼                                      │
 │            sync_db (Postgres)                          │
 │              users / refresh_tokens                    │
 └──────────────────────────────────────────────────────┘

            ▲
            │  Authorization: Bearer <access JWT>
            │  order-service validates signature locally
            ▼
 ┌──────────────────────────────────────────────────────┐
 │                    order-service                       │
 │  JwtAuthenticationFilter (validates HS256)             │
 │  JwtService (decode only)                              │
 │  VendorContext (request-scoped, audit only)           │
 └──────────────────────────────────────────────────────┘
```

### Token family model (locked)

- Each user has **at most one active token family**. A family is a UUID
  shared by every refresh token issued during one continuous session lineage
  for that user.
- On login: find an existing non-revoked row for the user → reuse that
  `token_family`; otherwise `UUID.randomUUID()`.
- On refresh: the server hashes (SHA-256) the presented token and looks up
  by `token_hash`. If the row exists and is not revoked and not expired,
  the server **marks the presented row revoked** and inserts a new row with
  the same `token_family`.
- Theft detection: a refresh token whose row is already `revoked=true`
  causes the family to be burned (`UPDATE … WHERE token_family = ?`) and
  the request is rejected with `401 token_revoked`. The user must re-login.
- Expiry: `expires_at < now()` → `401 token_expired`, family is **not**
  burned (lazy expiration, no audit churn).

---

## 2. Package layout

### `sync-service` — root `com.sales.sync`

```text
src/main/java/com/sales/sync/auth/
  controller/    AuthController.java
  service/       AuthService.java
  service/       RefreshRotationService.java
  security/      JwtService.java                       # HS256 sign + parse
  security/      RefreshTokenCodec.java                # opaque UUID + SHA-256
  security/      JwtAuthenticationFilter.java          # present; no-op for /api/v1/auth/**
  security/      JwtSecretValidator.java               # @PostConstruct fail-fast
  security/      JwtSecretEnvironmentPostProcessor.java # bean for META-INF/spring.factories
  security/      VendorContext.java                    # request-scoped, optional
  support/       BearerMaskingConverter.java           # logback masking
  support/       AuthExceptionHandler.java             # @RestControllerAdvice
  support/       ErrorCode.java                        # enum
  repository/    UserRepository.java
  repository/    RefreshTokenRepository.java
  model/         User.java
  model/         RefreshToken.java
  dto/           LoginRequest.java
  dto/           RefreshRequest.java
  dto/           AuthResponse.java
  dto/           ErrorResponse.java

src/test/java/com/sales/sync/auth/
  service/       AuthServiceTest.java                  # *Test, Mockito only
  service/       RefreshRotationServiceTest.java
  security/      JwtServiceTest.java
  security/      JwtSecretValidatorTest.java
  controller/    AuthControllerWebMvcTest.java         # @WebMvcTest slice
  it/            AbstractPostgresIT.java               # Testcontainers base
  it/            AuthLoginIT.java
  it/            LockedAccountIT.java
  it/            RefreshRotationIT.java
  it/            RefreshFamilyBurnIT.java
  it/            ExpiredTokenIT.java
  it/            JwtSecretValidatorIT.java             # boot fail-fast
```

### `order-service` — root `com.sales.order`

```text
src/main/java/com/sales/order/auth/
  security/      JwtAuthenticationFilter.java          # OncePerRequestFilter
  security/      JwtService.java                       # decode only (Nimbus)
  security/      JwtSecretValidator.java
  security/      JwtSecretEnvironmentPostProcessor.java
  security/      VendorContext.java                    # request-scoped holder (Optional<UUID>)
  support/       BearerMaskingConverter.java
  support/       AuthExceptionHandler.java

src/test/java/com/sales/order/auth/
  security/      JwtAuthenticationFilterTest.java      # *Test, Mockito
  security/      VendorContextTest.java
  it/            AbstractPostgresIT.java               # for symmetry with sync-service
```

Notes:
- `JwtService` exists in both services; only `sync-service`'s instance signs.
- `UserRepository` / `RefreshTokenRepository` exist only in `sync-service`.
- `order-service` does not own a datasource in this change.

---

## 3. Configuration

### 3.1 `sync-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  forward-headers-strategy: framework

spring:
  application:
    name: sync-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:sync_db}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate                # Flyway owns the schema
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    clean-disabled: false               # tests use @SpringBootTest + flyway.clean()

jwt:
  secret: ${JWT_SECRET}                 # required, ≥32 bytes (utf-8)
  access-token-ttl: 900s                # 15 min (A3)
  refresh-token-ttl: 604800s            # 7 days (A4)
  issuer: hielo-sync
  audience: hielo-order

security:
  bcrypt-cost: ${BCRYPT_COST:12}        # A1 prod / A2 test override
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:4200}

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
```

### 3.2 `order-service/src/main/resources/application.yml`

```yaml
server:
  port: 8081
  forward-headers-strategy: framework

spring:
  application:
    name: order-service
  # No datasource this change (DB work owned by core-orders).

jwt:
  secret: ${JWT_SECRET}                 # same value as sync-service in every env
  access-token-ttl: 900s                # for symmetry; not used in this slice
  refresh-token-ttl: 604800s
  issuer: hielo-sync                    # MUST match signer
  audience: hielo-order                 # MUST match expected aud claim

management:
  endpoints:
    web:
      exposure:
        include: health
```

Both services bind `JwtProperties` via `@ConfigurationProperties(prefix = "jwt")`.

---

## 4. Database schema

`sync-service/src/main/resources/db/migration/V1__create_users_and_refresh_tokens.sql`:

```sql
-- V1 owned by auth-foundation-sync. No other change may use V1 here.

CREATE EXTENSION IF NOT EXISTS pgcrypto;        -- provides gen_random_uuid()

CREATE TABLE users (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email           varchar(320) UNIQUE NOT NULL,
  password_hash   varchar(72)  NOT NULL,         -- BCrypt fits in 60; 72 = upper bound
  vendor_id       uuid         NULL,             -- A7 informational only
  locked          boolean      NOT NULL DEFAULT false,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_family    uuid NOT NULL,
  token_hash      varchar(64) NOT NULL UNIQUE,    -- SHA-256 hex
  expires_at      timestamptz NOT NULL,
  revoked         boolean NOT NULL DEFAULT false,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_refresh_tokens_user_id     ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family      ON refresh_tokens (token_family);
CREATE INDEX ix_refresh_tokens_revoked_exp ON refresh_tokens (revoked, expires_at);
```

Notes:
- BCrypt has no DB representation (A1/A2 cost handled in app via
  `BCryptPasswordEncoder`).
- `token_hash` is `UNIQUE` to make the refresh lookup collision-resistant
  and index-supported.
- `updated_at` is updated by a JPA `@PreUpdate` hook on `User`.

---

## 5. JWT handling

Library: `com.nimbusds:nimbus-jose-jwt` (already on Spring Security 3.2.5's
classpath via `spring-boot-starter-oauth2-resource-server`). HS256 via
`JWSAlgorithm.HS256`.

```java
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
    String secret,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    String issuer,
    String audience
) {}
```

`JwtService` (sign + parse; only `sync-service` signs, both parse):

```java
@Component
public class JwtService {
  private final JWSEncoder encoder;     // sync-service only
  private final JWSVerifier verifier;   // both services
  private final JwtProperties props;
  // sign(userId, vendorId) -> String (JWS compact)
  //   JWTClaimsSet: sub=userId, vendor_id claim, iss=issuer, aud=audience, iat, exp, jti
  // parse(token) -> JWTClaimsSet or throws (signature/iss/aud/exp failure)
}
```

`RefreshTokenCodec`:

```java
@Component
public class RefreshTokenCodec {
  // generate() -> OpaqueRefreshToken { plaintext: UUID.randomUUID().toString(),
  //                                    hash: sha256Hex(plaintext) }
  // verifyHash(plaintext) -> hash (used only by rotation lookup; constant-time compare unnecessary)
}
```

Justification:
- **Nimbus over jjwt:** Spring Security 3.2.5 already imports Nimbus via the
  OAuth2 resource server starter; one fewer dependency.
- **HS256 over RS256:** the two services trust the same secret in the
  internal network; HS256 keeps encoder/decoder symmetric, simpler key
  rotation. RS256 belongs to a future change if asymmetric distribution is
  required.
- **Access token** = compact JWS. Claims: `sub`, `vendor_id`, `iss`,
  `aud`, `iat`, `exp`, `jti`.
- **Refresh token** = opaque UUID v4 (A8); only its SHA-256 hash is stored.
  A DB read does not leak usable tokens.

---

## 6. Refresh rotation algorithm

`RefreshRotationService.rotate(presentedPlaintext)` — single `@Transactional`
method (REQUIRED propagation):

```text
1.  hash := sha256Hex(presentedPlaintext)              // 64 hex chars
2.  row  := refreshTokenRepo.findByTokenHash(hash)     // no revoked filter here
3.  if row == null                       -> throw TokenInvalidException   // 401 token_revoked
4.  if row.expiresAt.isBefore(now())    -> throw TokenExpiredException   // 401 token_expired; family NOT burned
5.  if row.revoked == true              -> burnFamily(row.tokenFamily)
                                          -> throw TokenRevokedException  // 401 token_revoked
6.  UPDATE refresh_tokens SET revoked = true WHERE id = row.id
7.  access := JwtService.sign(row.userId, vendorId)
8.  rt := RefreshTokenCodec.generate()
    INSERT refresh_tokens (id, user_id, token_family, token_hash,
                           expires_at, revoked)
    VALUES (gen_random_uuid(), row.userId, row.tokenFamily,
            rt.hash(), now() + refreshTtl, false)
9.  return AuthResponse(access, rt.plaintext(), props.accessTokenTtl().toSeconds())
```

`burnFamily(uuid family)`:

```text
UPDATE refresh_tokens SET revoked = true
 WHERE token_family = :family AND revoked = false;
```

Step-ordering rationale:
- Step 2 looks up by `token_hash` *regardless* of `revoked`. That is what
  makes theft detection work — a presented-but-already-revoked token must
  hit step 5, not be silently accepted as "not found".
- Step 4 (expired) deliberately does **not** burn the family. Expiry is
  benign; burning on expiry would lock users out across deployments and
  pump audit churn.
- Steps 2–9 run in one `@Transactional` block. A crash between step 6 and
  step 8 leaves the presented row consumed and the user simply re-logs in
  — preferable to issuing two live tokens in the same family.

---

## 7. Token family creation/join logic

`AuthService.login(email, password)`:

```text
1.  user := userRepo.findByEmail(email.toLowerCase()).orElseThrow(InvalidCredentials)
2.  if user.locked                                 -> throw AccountLockedException
3.  if !passwordEncoder.matches(pwd, user.pwdHash) -> throw InvalidCredentials
4.  family := refreshTokenRepo
                 .findActiveFamilyByUserId(user.id)        // SELECT ... LIMIT 1
                 .orElseGet(() -> UUID.randomUUID())
5.  access := JwtService.sign(user.id, user.vendorId)
6.  rt     := RefreshTokenCodec.generate()
    INSERT refresh_tokens (..., token_family = family, token_hash = rt.hash(), ...)
7.  return AuthResponse(access, rt.plaintext(), ttl)
```

```java
@Query(value = """
  SELECT token_family FROM refresh_tokens
   WHERE user_id = :userId AND revoked = false
   ORDER BY created_at DESC LIMIT 1
  """, nativeQuery = true)
Optional<UUID> findActiveFamilyByUserId(UUID userId);
```

- "Active family" = at least one non-revoked row exists for the user. If
  none, mint a new family.
- A burn (step 5 of the rotation algorithm) revokes every row, so the next
  login correctly mints a fresh family.

---

## 8. Security configuration

### `sync-service` — `SecurityConfig`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http,
                                  JwtAuthenticationFilter jwtFilter,
                                  CorsConfigurationSource cors) throws Exception {
    return http
      .csrf(CsrfConfigurer::disable)
      .cors(c -> c.configurationSource(cors))
      .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
      .authorizeHttpRequests(a -> a
          .requestMatchers("/api/v1/auth/**").permitAll()
          .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
          .anyRequest().authenticated())
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
      .exceptionHandling(e -> e
          .authenticationEntryPoint((req, res, ex) -> {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"token_expired\"}");
          }))
      .build();
  }
}
```

### `order-service` — `SecurityConfig`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http,
                                  JwtAuthenticationFilter jwtFilter) throws Exception {
    return http
      .csrf(CsrfConfigurer::disable)
      .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
      .authorizeHttpRequests(a -> a
          .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
          .anyRequest().authenticated())
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
      .exceptionHandling(e -> e
          .authenticationEntryPoint((req, res, ex) -> {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"token_expired\"}");
          }))
      .build();
  }
}
```

### `JwtAuthenticationFilter.doFilterInternal`

```text
1. Read Authorization header.
2. If missing or not "Bearer ...": chain.doFilter; downstream returns 401 if protected.
3. JwtService.parse(token):
     - signature/iss/aud/exp checks
     - on failure -> entry point writes 401 with token_expired or token_revoked
4. Build UsernamePasswordAuthenticationToken(principal=userId, authorities=[ROLE_USER]).
5. Populate request-scoped VendorContext (Optional<UUID> vendorId from claims).
6. SecurityContextHolder.getContext().setAuthentication(...); chain.doFilter.
7. finally clear VendorContext.
```

`VendorContext` is a `@RequestScope` bean.

---

## 9. `JWT_SECRET` fail-fast

Choice: **`EnvironmentPostProcessor` registered in `META-INF/spring.factories`**.

Reasoning: `EnvironmentPostProcessor` runs before `ApplicationContext`
creation, so the app fails fast at boot with a clear message rather than
during the first request or via `@PostConstruct` of a bean other beans
depend on. `@PostConstruct` on `JwtSecretValidator` is kept for unit tests
that bypass `SpringApplication.run()`.

```java
public class JwtSecretEnvironmentPostProcessor implements EnvironmentPostProcessor {
  @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    String secret = env.getProperty("jwt.secret");
    if (secret == null || secret.isBlank())
      throw new IllegalStateException("jwt.secret (JWT_SECRET env) is required");
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32)
      throw new IllegalStateException("jwt.secret must be >= 32 bytes (got " + key.length + ")");
  }
}
```

Both services register the equivalent class in their
`META-INF/spring.factories`:

```text
org.springframework.boot.env.EnvironmentPostProcessor=\
com.sales.{sync|order}.auth.security.JwtSecretEnvironmentPostProcessor
```

`JwtSecretValidator` (`@Component`) exposes the same check as `@PostConstruct`
for tests, used as `Validator<JwtProperties>` via `@Validated` on the
`@ConfigurationProperties` bean. Both layers present in both services.

---

## 10. Testcontainers base

`sync-service/src/test/java/com/sales/sync/auth/it/AbstractPostgresIT.java`:

```java
@Testcontainers
public abstract class AbstractPostgresIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withDatabaseName("sync_db")
          .withUsername("postgres")
          .withPassword("postgres");

  static { POSTGRES.start(); }

  @DynamicPropertySource
  static void register(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.flyway.clean-disabled", () -> "false");
  }
}
```

Every `*IT` extends `AbstractPostgresIT` and carries
`@SpringBootTest(webEnvironment = RANDOM_PORT)`. Unit tests (`*Test`) do
**not** extend the base and use plain `MockitoJUnitRunner`.

`order-service/src/test/java/com/sales/order/auth/AbstractPostgresIT.java`
mirrors the same class (no DB tests this change, but the base is committed
so `core-orders` can extend it without churning).

---

## 11. Test profile

`sync-service/src/test/resources/application-test.yml`:

```yaml
jwt:
  secret: "test-secret-test-secret-test-secret-32+bytes"   # 41 bytes, ≥32
  access-token-ttl: 900s
  refresh-token-ttl: 604800s
  issuer: hielo-sync
  audience: hielo-order

spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    clean-disabled: false                 # A2: tests may call flyway.clean

security:
  bcrypt-cost: 4                          # A2
```

Activated via `@ActiveProfiles("test")` on `*IT` classes. `*Test` slice
tests do not load the full context.

---

## 12. CORS

`sync-service` only. `order-service` is not browser-facing in this change.

```java
@Bean
CorsConfigurationSource cors(@Value("${security.cors.allowed-origins}") List<String> origins) {
  var cfg = new CorsConfiguration();
  cfg.setAllowedOrigins(origins);                       // explicit allowlist
  cfg.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
  cfg.setAllowedHeaders(List.of("Authorization","Content-Type"));
  cfg.setExposedHeaders(List.of("Authorization"));
  cfg.setAllowCredentials(true);
  cfg.setMaxAge(3600L);
  var src = new UrlBasedCorsConfigurationSource();
  src.registerCorsConfiguration("/api/v1/auth/**", cfg);
  src.registerCorsConfiguration("/api/**", cfg);
  return src;
}
```

Rules:
- No wildcard origins (`*`). Origins come from `CORS_ALLOWED_ORIGINS`.
- `/api/v1/auth/**` is explicitly registered (login + refresh).

---

## 13. Logging

`sync-service/src/main/resources/logback-spring.xml`:

```xml
<configuration>
  <conversionRule conversionWord="masked"
    converterClass="com.sales.sync.auth.support.BearerMaskingConverter"/>

  <appender name="STDOUT" class="chassisConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %masked%n</pattern>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

`BearerMaskingConverter`:

```java
public class BearerMaskingConverter extends ClassicConverter {
  private static final Pattern BEARER =
      Pattern.compile("(?i)(authorization[\\s:]+bearer\\s+)[^\\s]+");
  @Override public String convert(ILoggingEvent e) {
    return BEARER.matcher(e.getMessage()).replaceAll("$1***");
  }
}
```

The same converter is dropped into `order-service/src/main/resources/logback-spring.xml`.
The pattern matches `Authorization: Bearer eyJ...` and `authorization bearer eyJ...`,
replacing only the token portion with `***`.

---

## 14. Endpoint contracts (DTOs)

```java
public record LoginRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(min = 8, max = 128) String password
) {}

public record RefreshRequest(
    @NotBlank String refresh_token          // snake_case JSON, per spec
) {}

public record AuthResponse(
    String access_token,
    String refresh_token,
    long   expires_in                       // seconds
) {}

public record ErrorResponse(String error) {}
```

HTTP / `error` body mapping (`AuthExceptionHandler`):

| Exception                          | HTTP | `error` body          |
|------------------------------------|------|-----------------------|
| `InvalidCredentialsException`      | 401  | `invalid_credentials` |
| `AccountLockedException`           | 423  | `account_locked`      |
| `TokenExpiredException`            | 401  | `token_expired`       |
| `TokenRevokedException`            | 401  | `token_revoked`       |
| `TokenInvalidException`            | 401  | `token_revoked`       |
| `MethodArgumentNotValidException`  | 400  | `invalid_request`     |

`AuthExceptionHandler` is `@RestControllerAdvice` scoped to
`com.sales.sync.auth.controller`.

---

## 15. Test plan order (RED-first within each batch)

### Batch 1 — `auth-foundation-sync`

1. RED `JwtSecretValidatorTest` — empty/short `JWT_SECRET` → `IllegalStateException`.
2. RED `AuthLoginIT` — happy path login → 200 + access + refresh.
3. RED `AuthLoginIT` — wrong password → 401 `invalid_credentials`.
4. RED `LockedAccountIT` — locked user → 423 `account_locked`.
5. RED `RefreshRotationIT` — happy refresh → new tokens, old `revoked=true`.
6. RED `RefreshRotationIT` — replay of used refresh → 401 + family burned.
7. RED `RefreshFamilyBurnIT` — after burn, every token in family is 401.
8. RED `ExpiredTokenIT` — `expires_at < now()` → 401 `token_expired`, family NOT burned.
9. RED `JwtServiceTest` — sign/parse round-trip with iss/aud/exp/vendor_id.
10. RED `AuthControllerWebMvcTest` — request validation + error JSON shape.

### Batch 2 — `auth-foundation-order`

1. RED `JwtAuthenticationFilterTest` — missing header → anonymous; protected → 401.
2. RED `JwtAuthenticationFilterTest` — invalid signature/iss/aud → 401 `token_revoked`.
3. RED `JwtAuthenticationFilterTest` — valid token → `SecurityContext` populated + `VendorContext` holds `vendor_id`.
4. RED `AuthExceptionHandlerTest` (order-service) — maps `TokenExpired`/`TokenRevoked` → JSON.
5. RED `JwtSecretValidatorIT` (order-service) — boot fails with `JWT_SECRET` < 32 bytes.

Each RED is followed by its GREEN within the same PR.

---

## 16. Migration ordering

- `auth-foundation-sync` exclusively owns
  `sync-service/src/main/resources/db/migration/V1__create_users_and_refresh_tokens.sql`.
- `auth-foundation-order` introduces **no** Flyway migration in either service.
- No other change (`core-orders`, anything future) may use `V1` in
  `sync-service`; subsequent changes in `sync-service` start at `V2__...sql`.

---

## 17. Risks and mitigations

| Risk | Mitigation |
|---|---|
| `JWT_SECRET` leak (HS256 shared key) | Single source: env var; fail-fast ≥ 32 bytes; `JwtSecretValidator` in both services. Rotation: coordinated redeploy with overlapping refresh-token validity (7 d). |
| Refresh token theft via DB read | Store only SHA-256 hash (`varchar(64) UNIQUE`); plaintext never persisted. |
| Replay across two parallel refresh requests (race) | `@Transactional` + `findByTokenHash` + `UPDATE ... WHERE revoked=false`. Second concurrent request reads `revoked=true` post-commit → 401 + family burn. |
| Time-zone drift on `expires_at` comparisons | `timestamptz` everywhere; `hibernate.jdbc.time_zone=UTC`; JPA `Instant`. |
| BCrypt cost too high in tests (A2) | `application-test.yml` overrides `security.bcrypt-cost=4`. |
| BCrypt hash > 72 bytes truncated | `password_hash varchar(72)`; BCrypt output capped at 60. |
| `vendor_id` claim absent for non-vendor users (A7) | JWT carries `vendor_id` as nullable; `VendorContext.get()` returns `Optional<UUID>`. |
| Family proliferation per device | Spec locks "one active family per user"; new device login reuses the family. Revisit in a follow-up if multi-device sessions become a product need. |

Design-introduced risks (flagged):
- **Single active family per user**: logging in on a second device does not
  invalidate refresh on the first; rotation is what discovers theft on the
  first device. Acceptable for v1, explicitly locked in the spec.
- **No blacklist table** beyond `revoked=true` rows; logout is "revoke
  current family" via a future endpoint not in this change.

---

## 18. Workload

| Batch | Files | Est. lines |
|---|---|---|
| `auth-foundation-sync` | sync-service pom + migration + 12 src + 6 test + logback/spring.factories + test profile | ≈ 1,065 |
| `auth-foundation-order` | order-service pom + 4 src + 3 test + logback/spring.factories | ≈ 375 |
| Total |  | ≈ 1,440 |

Per `auto-forecast` strategy, this triggers the chained PR split already
locked in the proposal.

---

## Out of scope (explicit)

- Logout endpoint (rotate-and-revoke current refresh) — follow-up change.
- Multi-device session support / multi-family per user.
- `order-service` datasource, schema, and the `core-orders` change.
- KMS-backed `JWT_SECRET` rotation tooling.
- `/api/v1/users/**` self-service endpoints (registration, password reset).

---

## skill_resolution

`skill_resolution: none` — no project/user skill paths injected.
