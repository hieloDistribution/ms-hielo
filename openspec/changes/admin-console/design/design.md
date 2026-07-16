# Design — admin-console

> Phase result for the `admin-console` change in project `hielo`. Drives implementation in `sdd-tasks` and `sdd-apply`. MUST be read together with `proposal/proposal.md`, `specs/admin-bootstrap/spec.md`, and `specs/admin-roles/spec.md`, and the canonical auth spec at `openspec/specs/auth/spec.md`.

## 1. Status

`design-locked — open items listed in section 14 are decisions deferred to sdd-tasks, not blockers.`

## 2. Architectural Decisions

### 2.1 Service ownership

All new admin endpoints live in **`sync-service`**, not order-service. Rationale:

- `users`, `refresh_tokens`, and any new identity tables (`admin_invites`, `admin_audit_log`) live in sync-service's database. Admin endpoints operate on those tables.
- `order-service` already has a separate database for clients/vendors/products/drivers and would need a service-to-service HTTP hop for every admin call. That latency and complexity buys nothing.
- `JwtService` is already implemented in both services with the same secret. Updating both to dual-shape parsing is mechanical; centralizing the admin surface in sync-service means the issuer (sync-service) and the admin endpoints share the same DB transaction.

The `/api/v1/admin/**` paths in sync-service follow the same pattern as the existing `/api/v1/auth/**` and `/api/v1/users/**` endpoints in sync-service. The Flutter client calls them on port 8081.

### 2.2 Single source of role authority

The **`users` table is the only authority for role membership**. The JWT carries the roles claim for routing (gate) and the audit log records writes, but the gate in `RoleGateFilter` re-queries the database for `active=true AND 'admin' = ANY(roles)` before honoring an admin path. This mirrors the canonical "vendor_id is informational only" rule from `openspec/specs/auth/spec.md`.

This is non-negotiable. A deactivated admin with a still-valid token MUST NOT reach admin endpoints.

### 2.3 Idempotent JWT issuer

Sync-service issues JWTs. Order-service only parses them. Both services accept a dual-shape token (single-string `role` OR array `roles`) for the duration of the cut-over window. Cut-over is finished in PR5.

### 2.4 Multi-tenancy assumption

This change assumes single-tenant. No `tenant_id` is added to the schema. If the company ever splits into multiple tenants, that is a separate change with its own data-model and gate.

## 3. Data Model

### 3.1 Flyway migrations on `sync-service/src/main/resources/db/migration/`

Two new files. Both files MUST start with a header comment naming the change that owns them, per the convention already used in V1, V4, V5.

#### `V6__admin_roles_and_status.sql` (PR2 / PR3)

```sql
-- V6 owned by change admin-console (PR2 admin-bootstrap-seeder, PR3 admin-roles-gate).
-- Adds:
--   * users.roles TEXT[]            multi-role authority column
--   * users.active BOOLEAN          soft-delete / activation flag
--   * users.must_change_password   force-change after bootstrap / reactivate
--   * users.version BIGINT          optimistic locking for role mutations
-- Backwards-compatible: keeps users.role for cut-over window.

ALTER TABLE users
    ADD COLUMN roles            TEXT[]         NOT NULL DEFAULT ARRAY['cliente']::TEXT[];

ALTER TABLE users
    ADD COLUMN active            BOOLEAN        NOT NULL DEFAULT TRUE;

ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE;

ALTER TABLE users
    ADD COLUMN version           BIGINT         NOT NULL DEFAULT 0;

-- Backfill roles from the existing single-string role column.
-- All existing rows have role NOT NULL with a CHECK constraint
-- (admin|repartidor|cliente), so ARRAY[role] is safe.
UPDATE users
SET roles = ARRAY[role]::TEXT[]
WHERE roles = ARRAY['cliente']::TEXT[]
  AND role IS NOT NULL
  AND role <> 'cliente';

-- Drop the old single-string CHECK constraint but keep the column for cut-over.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_chk;

-- New GIN index on the array column so DB-level role queries stay fast.
CREATE INDEX idx_users_roles_gin ON users USING GIN (roles);
CREATE INDEX idx_users_active    ON users (active);
```

Notes:

- The `roles = ARRAY['cliente']::TEXT[]` default keeps NOT NULL safe for any rows that arrive between `ADD COLUMN` and the backfill.
- The GIN index makes `WHERE 'admin' = ANY(roles)` and `WHERE roles && ARRAY['admin']` queries index-friendly.
- We do not drop the `role` column yet. PR5 does that.
- We do drop the old CHECK constraint because the backfill populates `roles` from `role` and downstream reads use `roles`.

#### `V7__admin_invites_and_audit.sql` (PR4)

```sql
-- V7 owned by change admin-console (PR4 admin-roles-management).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE admin_invites (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           varchar(320) NOT NULL,
    role            varchar(20)  NOT NULL,
    token_hash      varchar(72)  NOT NULL UNIQUE,        -- bcrypt(token)
    expires_at      timestamptz  NOT NULL,
    used_at         timestamptz  NULL,
    created_by      uuid         NOT NULL REFERENCES users(id),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT admin_invites_role_chk CHECK (role IN ('admin', 'repartidor'))
);

CREATE INDEX idx_admin_invites_email      ON admin_invites (email);
CREATE INDEX idx_admin_invites_expires_at ON admin_invites (expires_at);

CREATE TABLE admin_audit_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id   uuid         NULL,                    -- NULL only for signup_role_ignored
    action          varchar(64)  NOT NULL,
    target_user_id  uuid         NULL,
    target_email    varchar(320) NULL,
    before_json     jsonb        NULL,
    after_json      jsonb        NULL,
    request_id      varchar(64)  NOT NULL,
    notes           text         NULL,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_log_created_at ON admin_audit_log (created_at DESC);
CREATE INDEX idx_admin_audit_log_actor      ON admin_audit_log (actor_user_id);
CREATE INDEX idx_admin_audit_log_target     ON admin_audit_log (target_user_id);
CREATE INDEX idx_admin_audit_log_action     ON admin_audit_log (action);

-- Append-only. Even the application role cannot UPDATE or DELETE rows;
-- only INSERT and SELECT. Migrations can drop and re-create if schema changes.
REVOKE UPDATE, DELETE ON admin_audit_log FROM PUBLIC;
REVOKE UPDATE, DELETE ON admin_audit_log FROM <app_role>;   -- to be filled with the
                                                             -- actual runtime role name
                                                             -- at task time.
```

Notes:

- The `<app_role>` placeholder is filled in at task time with the actual Postgres role used by the application connection (commonly `ice_app` or the literal username in `application.yml`). The blank migration file is committed with the placeholder; the `sdd-apply` step replaces it with the actual role name as part of running the migration against the dev DB.
- `REVOKE` runs in the same migration file so it is enforced from the first deploy.
- `before_json` / `after_json` capture role snapshots at change time. Useful when debugging "what did this user look like before the demotion" two months later.
- `request_id` is a server-generated UUID per request, attached as a request attribute in a `RequestIdFilter` (added in PR4) and threaded through the audit writer.

#### `V8__drop_legacy_role_column.sql` (PR5 — final cut-over)

```sql
-- V8 owned by change admin-console (PR5 admin-roles-cutover).
-- Final cut-over: drop the legacy users.role column and the index on it.

ALTER TABLE users DROP COLUMN IF EXISTS role;
DROP INDEX IF EXISTS idx_users_role;
```

PR5 is gated by the deploy check that NO service is still issuing or accepting single-string `role` claims.

### 3.2 Hibernate / JPA impact

The `User` entity in `sync-service` is updated in PR3:

- Replace `Role role` with `Set<User.Role> roles`, using a custom Hibernate UserType backed by `String[]` for Postgres `TEXT[]`. The set semantics are what callers want; the DB stores a sorted, deduped array.
- Add `boolean active`, `boolean mustChangePassword`, `long version` (with `@Version`).
- Keep `vendorId`, `locked`, etc. untouched.
- The legacy `role` column stays mapped as a deprecated, read-only field that the seeder backfill uses, until V8 drops it.

The User entity in order-service does NOT need changes — order-service does not own the `users` table and does not map `User`. It only consumes the JWT and exposes contexts.

## 4. JWT claim migration mechanics

The dual-shape window is the trickiest piece. Both services need to:

1. **Issue**: write `roles` (array) as the authoritative claim; ALSO write `role` (single-string, deprecated) for back-compat during cut-over. The legacy claim equals the first role in the new array.
2. **Parse**: prefer `roles` (array); fall back to `role` (string) wrapped into a singleton set.
3. **Authorities**: map each role in the resolved set to `ROLE_<UPPER>` Spring Security authority.

### 4.1 Token shape during cut-over (PR3 / PR4)

```json
{
  "sub": "uuid",
  "email": "u@hielo.com",
  "vendor_id": "uuid|null",
  "role": "admin",            // legacy, kept for cut-over
  "roles": ["admin"],         // new, authoritative
  "iss": "hielo-sync",
  "aud": "hielo-order",
  "iat": 1784200000,
  "exp": 1784200900,
  "jti": "uuid",
  "mcp": false                // must_change_password; see section 5.3
}
```

### 4.2 Final shape (PR5)

```json
{
  "sub": "uuid",
  "email": "u@hielo.com",
  "vendor_id": "uuid|null",
  "roles": ["admin"],
  "iss": "hielo-sync",
  "aud": "hielo-order",
  "iat": 1784200000,
  "exp": 1784200900,
  "jti": "uuid",
  "mcp": false
}
```

The `role` claim is gone. Order-service no longer reads it. A token with only `role` after PR5 is rejected with `401 invalid_token`. The `mvn test` matrix in PR5 includes a regression test that issues a legacy token, ships it to a freshly-built service, and asserts the rejection.

### 4.3 Cut-over completion criteria

PR5 is merged only when:

- All sync-service pods are running PR3+ code that writes `roles`.
- All order-service pods are running PR3+ code that parses `roles`.
- At least one full deploy cycle has elapsed (so any cached tokens were issued with `roles`).
- A grep audit over the codebase confirms no remaining references to `c.getStringClaim("role")` in production code paths (test fixtures may keep it).

The grep audit is automated in PR5's CI gate: a shell test in the test runner that fails if the regex `getStringClaim\("role"\)` appears outside of files under `src/test/`.

## 5. Backend — sync-service

### 5.1 Package layout

New files go under `com.sales.sync.auth.admin.*`:

```
com.sales.sync.auth.admin/
  AdminBootstrap.java               -- ApplicationRunner for first-boot seeding (PR2)
  AdminBootstrapProperties.java      -- @ConfigurationProperties("app.admin")
  AdminContext.java                  -- thread-local set of role names, mirroring VendorContext (PR3)
  AdminRoleGateFilter.java           -- OncePerRequestFilter on /api/v1/admin/** (PR3)
  RoleRequeryService.java            -- re-queries users table for active+role state (PR3)
  AuditLogWriter.java                -- INSERTs into admin_audit_log (PR4)
  RequestIdFilter.java               -- sets request_id request attribute (PR4)
  InviteTokenCodec.java              -- HMAC-SHA256 over base64url JSON (PR4)
  InviteTokenProperties.java         -- @ConfigurationProperties("app.invite")
  AdminService.java                  -- orchestration: list, change roles, deactivate, reactivate, invite (PR4)
  AdminController.java               -- @RestController for /api/v1/admin/** (PR3 + PR4)
  AdminInviteRedeemController.java   -- @RestController for /api/v1/auth/admin/invites/redeem (PR4; public)
  AdminListResponse.java             -- DTO (PR4)
  AdminRolePatchRequest.java         -- DTO (PR4)
  AdminDeactivateRequest.java        -- DTO (PR4; empty body accepted, kept for forward-compat)
  AdminReactivateRequest.java        -- DTO (PR4)
  AdminInviteRequest.java            -- DTO (PR4)
  AdminInviteResponse.java           -- DTO (PR4)
  AdminInviteRedeemRequest.java      -- DTO (PR4)
  AdminInviteRedeemResponse.java     -- DTO (PR4)
  AdminAuditLogEntry.java            -- DTO (PR4)
  AdminAuditLogResponse.java         -- DTO (PR4)
  AdminUserSummary.java              -- DTO (PR4)
  InviteRateLimiter.java             -- in-memory token bucket per IP (PR4)
  LastAdminGuard.java                -- helper that throws CannotSelfDemoteLastAdmin / CannotDeactivateLastAdmin (PR4)
```

Modified files:

```
com.sales.sync.auth.model.User.java          -- add roles, active, mustChangePassword, version (PR3)
com.sales.sync.auth.dto.SignupRequest.java   -- REMOVE role field (PR1)
com.sales.sync.auth.service.SignupService.java  -- force roles = [cliente], audit if hidden role field appears (PR1)
com.sales.sync.auth.dto.AuthResponse.java    -- add must_change_password boolean (PR2)
com.sales.sync.auth.service.AuthService.java -- include must_change_password in login response (PR2)
com.sales.sync.auth.security.JwtService.java -- write+read roles[] (PR3)
com.sales.sync.auth.security.JwtAuthenticationFilter.java  -- map Set<Role> to authorities (PR3)
com.sales.sync.auth.security.SecurityConfig.java  -- add /api/v1/admin/** to the filter chain ordering (PR3)
com.sales.sync.auth.dto.UserResponse.java    -- expose roles[] and active (PR4)
com.sales.sync.auth.repository.UserRepository.java  -- new query methods (PR4)
```

### 5.2 Controller map

| Method | Path | Service | Auth gate | Implementation PR |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/signup` | SignupService | public | PR1 |
| POST | `/api/v1/auth/admin/invites/redeem` | InviteRedeemService | public + rate limit | PR4 |
| POST | `/api/v1/admin/invites` | AdminService.issueInvite | `@PreAuthorize("hasRole('ADMIN')")` + RoleGateFilter | PR4 |
| GET  | `/api/v1/admin/users` | AdminService.listUsers | same | PR4 |
| GET  | `/api/v1/admin/users/{id}` | AdminService.getUser | same | PR4 |
| PATCH | `/api/v1/admin/users/{id}/roles` | AdminService.changeRoles | same | PR4 |
| POST | `/api/v1/admin/users/{id}/deactivate` | AdminService.deactivate | same | PR4 |
| POST | `/api/v1/admin/users/{id}/reactivate` | AdminService.reactivate | same | PR4 |
| GET  | `/api/v1/admin/audit-log` | AdminService.listAuditLog | same | PR4 |

### 5.3 `must_change_password` propagation

To avoid a DB hit on every admin request, the bootstrap and reactivate flows also set a JWT claim:

- `AdminBootstrap` inserts user with `must_change_password=true`. The first login issues a token with `mcp=true`.
- `UserProfileService.changePassword()` clears the DB flag AND the user must re-login to get a clean token. (We do not silently rotate the access token in this PR slice because doing so adds complexity to refresh-token families; deferred.)
- `AuthService.login()` returns `must_change_password` in the response body so the client can route to a "change password" screen before going further.
- `RoleGateFilter` checks the `mcp` JWT claim for admin paths. If `mcp=true`, returns 403 with body `{"error":"must_change_password_required"}`. No DB hit.

The mcp claim is included from PR2 onwards and stays in the JWT shape through PR5.

### 5.4 `RoleGateFilter` mechanics

```java
@Component
@Order(40)  // higher priority than SecurityConfig's default chain
public class AdminRoleGateFilter extends OncePerRequestFilter {

    private static final AntPathRequestMatcher ADMIN_PATH =
        new AntPathRequestMatcher("/api/v1/admin/**");

    private final JwtService jwtService;
    private final RoleRequeryService roleRequery;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!ADMIN_PATH.matches(req)) {
            chain.doFilter(req, res);
            return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !hasAuthority(auth, "ROLE_ADMIN")) {
            reject(res, "admin_role_required");
            return;
        }
        // mcp claim
        String token = extractBearer(req);
        var parsed = jwtService.parse(token);
        if (parsed.mustChangePassword()) {
            reject(res, "must_change_password_required");
            return;
        }
        // authority re-query
        if (!roleRequery.isActiveAdmin(parsed.userId())) {
            reject(res, "admin_role_required");
            return;
        }
        chain.doFilter(req, res);
    }
}
```

`RoleRequeryService.isActiveAdmin(userId)` runs:

```sql
SELECT active AND 'admin' = ANY(roles)
FROM users
WHERE id = ? AND active = TRUE
```

The `idx_users_active` and `idx_users_roles_gin` indexes keep this <5ms even at 100k users.

### 5.5 Last-admin guard

```java
@Component
public class LastAdminGuard {
    private final JdbcTemplate jdbc;
    public void requireNotLastAdmin(UUID selfUserId) {
        Long others = jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE 'admin' = ANY(roles) AND active = TRUE AND id <> ?",
            Long.class, selfUserId);
        if (others == null || others == 0) {
            throw new CannotSelfDemoteLastAdmin();
        }
    }
}
```

Used in:

- `AdminService.changeRoles()` — before applying a demotion of self.
- `AdminService.deactivate()` — before deactivating self.

Throws are mapped to `409 cannot_self_demote_last_admin` and `409 cannot_deactivate_last_admin` by `AdminExceptionHandler`.

### 5.6 Invite token format

Compact format (deliberately NOT a JWT, so that revocation is server-side via the `admin_invites` row rather than a JWT signing key):

```
<base64url(JSON payload)>.<base64url(HMAC-SHA256 signature)>
```

Payload:

```json
{
  "email": "u@hielo.com",
  "role": "admin",
  "jti": "uuid",
  "exp": 1784284800,
  "iat": 1784200000
}
```

Signature: HMAC-SHA256 over `<base64url(JSON payload)>` bytes using a key derived from `JWT_SECRET + ":admin-invite"` (domain separation). The same secret as JWT issuance but with a domain suffix so the two signatures cannot collide.

Storage: only the bcrypt hash of the full token string is persisted in `admin_invites.token_hash`. The plaintext token is returned exactly once at issue time.

Redeem verification order:

1. Split on `.`, decode both halves.
2. Recompute HMAC over the first half, constant-time compare with the second.
3. Reject if HMAC mismatch.
4. Reject if `exp` < now.
5. Look up `admin_invites` by `id = jti`.
6. Reject if no row.
7. Bcrypt-compare the full token string against `token_hash`. Reject on mismatch.
8. Reject if `used_at` is not null.
9. Create user, mark `used_at`, audit.

### 5.7 Rate limit on `/api/v1/auth/admin/invites/redeem`

`InviteRateLimiter` is a hand-rolled in-memory token bucket per IP. Single-tenant, single-instance assumption; we do not need a distributed limiter for this slice.

```java
class InviteRateLimiter {
    private final int capacity = 5;
    private final Duration window = Duration.ofHours(1);
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(String ip) {
        return buckets.computeIfAbsent(ip, k -> new Bucket(capacity, window)).tryConsume(1);
    }
}
```

On reject: `429 Too Many Requests` with `Retry-After: <seconds-until-refill>` header.

### 5.8 Audit log wiring

`AuditLogWriter.log(AuditEvent event)` runs in the same transaction as the state change:

```java
public void log(AuditEvent event) {
    jdbc.update("""
        INSERT INTO admin_audit_log
            (id, actor_user_id, action, target_user_id, target_email, before_json,
             after_json, request_id, notes, created_at)
        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, now())
        """,
        event.actorUserId(), event.action(), event.targetUserId(),
        event.targetEmail(), event.beforeJson(), event.afterJson(),
        event.requestId(), event.notes());
}
```

The `request_id` is generated by `RequestIdFilter` (header `X-Request-Id` if present, else `UUID.randomUUID().toString()`) and exposed via `RequestContextHolder` so service code can read it without plumbing it through every method signature.

## 6. Backend — order-service

### 6.1 What changes

Minimal — the admin endpoints live in sync-service.

Modified files:

```
com.sales.order.auth.security.JwtService.java               -- read roles[] claim, build ParsedToken with Set<Role> roles (PR3)
com.sales.order.auth.security.JwtAuthenticationFilter.java -- map Set<Role> to authorities (PR3)
com.sales.order.auth.security.SecurityConfig.java           -- no change (admin paths not present in order-service)
```

### 6.2 What does NOT change

- `SecurityConfig.java` stays `anyRequest().authenticated()`. Order-service has no admin paths.
- `InternalSecurityConfig.java` stays as-is. The `/internal/**` chain is untouched.
- No new migrations. order-service owns its own DB and does not touch `users` or new admin tables.

## 7. Frontend — Flutter (`UI-HieloPedido`)

### 7.1 File inventory

Modified:

```
lib/core/auth/token_storage.dart            -- role -> roles (set of strings), add mustChangePassword (PR2 + PR3)
lib/providers/order_provider.dart           -- decode roles[] on bootstrap, refreshUserRole returns Set<String> (PR3)
lib/screens/register_screen.dart            -- remove role selector; force CLIENT on submit (PR1)
lib/screens/admin_screen.dart               -- split into admin tabs (PR4)
lib/screens/main_navigation_screen.dart     -- branch on roles.contains('admin') (PR3)
lib/screens/login_screen.dart               -- react to mustChangePassword in login response (PR2)
lib/core/network/api_client.dart            -- new admin endpoints wrappers (PR4)
```

New:

```
lib/screens/sub_screens/admin/admin_console_screen.dart          -- entry point (PR4)
lib/screens/sub_screens/admin/admin_users_tab.dart               -- list + search + filter + actions (PR4)
lib/screens/sub_screens/admin/admin_invites_tab.dart             -- issue + list (PR4)
lib/screens/sub_screens/admin/admin_user_detail_sheet.dart       -- role change + deactivate/reactivate (PR4)
lib/screens/sub_screens/admin/admin_change_password_screen.dart  -- forced by mustChangePassword (PR2)
lib/providers/admin_provider.dart                               -- ChangeNotifier for admin UI state (PR4)
lib/models/admin_user.dart                                       -- data class (PR4)
lib/models/admin_invite.dart                                     -- data class (PR4)
lib/models/admin_audit_entry.dart                                -- data class (PR4)
```

### 7.2 Navigation rewrite

`main_navigation_screen.dart` branches on `roles.contains('admin')`:

- `roles = {admin, cliente}` → AdminConsole (full access).
- `roles = {admin}` → AdminConsole only.
- `roles = {repartidor}` → existing repartidor experience.
- `roles = {cliente}` → existing cliente experience.

If a user has multiple roles (e.g. `admin + cliente`), the navigation offers role switcher: tap avatar → "Modo: Admin / Cliente". Each mode has its own navigation root. The role-switch UX is a tab in the bottom nav, not a separate top-level route.

### 7.3 `AdminConsoleScreen` structure

Bottom-nav with three sections:

- **Usuarios** — `admin_users_tab`. Search bar, role filter chip row, paginated list. Each row: avatar, name, email, role chips, active badge if deactivated, kebab menu with "Cambiar rol" and "Desactivar/Reactivar".
- **Invitaciones** — `admin_invites_tab`. Form: email + role select + "Generar invitación" button. Below: list of pending invites with "Revocar" action and a copy-to-clipboard affordance for the invite link.
- **Operaciones** — keep the existing Resumen / Logística / Auditorías / Despachos tabs as read-only. This is a step backwards from the original AdminScreen, but explicitly out-of-scope for this change per the proposal.

### 7.4 Role-change UX

`admin_user_detail_sheet.dart` opens as a bottom sheet. Shows current roles as chips, with an "Edit" affordance. Tapping Edit reveals a multi-select chip row (`admin`, `repartidor`, `cliente`). Save triggers PATCH `/api/v1/admin/users/{id}/roles` with optimistic locking header. On 409 (version conflict), surface a "Otro admin cambió este usuario. Vuelve a abrir." toast. On success, close sheet and refresh list.

If the target user is the calling admin AND the operation removes `admin`, the sheet must surface the 409 `cannot_self_demote_last_admin` BEFORE the request fires: the API also enforces it, but the UX blocks the submission client-side as a first defense.

### 7.5 Invite UX

Form has email + role select (admin or repartidor). On submit, POST `/api/v1/admin/invites`, receive `{invite_id, token, expires_at}`. The screen displays:

> Invitación creada. Compartí este link:
>
> `https://app.hielo.com/auth/invite?token=<token>`
>
> Expira: `2026-07-17 12:00`.

With "Copiar link" and "Cerrar" buttons. The link is never re-fetchable: re-asking for the same invite is rejected (revoke + reissue).

### 7.6 `mustChangePassword` UX

On login response containing `must_change_password=true`, the app routes to `AdminChangePasswordScreen` BEFORE any other screen, regardless of the role set. The screen asks for current password + new password (≥12 chars). On success, it re-runs login (or uses the existing token to call change-password) and continues to the role-appropriate home.

## 8. API contract examples

### 8.1 `POST /api/v1/admin/invites`

Request:

```json
{ "email": "new.admin@hielo.com", "role": "ADMIN" }
```

Response (201):

```json
{
  "invite_id": "8a7c1c1c-...",
  "token": "eyJlbWFpbCI6...",
  "expires_at": "2026-07-17T12:00:00Z"
}
```

Error responses:

- `400` `{"error":"invalid_role"}` if role not in `{admin, repartidor}`.
- `401` `{"error":"token_expired"}` on bad JWT.
- `403` `{"error":"admin_role_required"}` if caller is not admin.
- `409` `{"error":"email_already_active"}` if the email is already an active user.

### 8.2 `POST /api/v1/auth/admin/invites/redeem`

Request:

```json
{ "token": "<full token>", "password": "new-password-12+chars" }
```

Response (201):

```json
{
  "user_id": "uuid",
  "email": "new.admin@hielo.com",
  "roles": ["ADMIN"],
  "active": true
}
```

Errors:

- `410 invite_already_used`.
- `410 invite_expired`.
- `410 invite_invalid` (HMAC mismatch).
- `422 weak_password` if password fails policy.
- `429 too_many_attempts` with `Retry-After`.

### 8.3 `GET /api/v1/admin/users`

Query: `?q=juan&role=ADMIN&page=1&page_size=20`.

Response (200):

```json
{
  "items": [
    {
      "id": "uuid",
      "email": "juan.admin@hielo.com",
      "full_name": "Juan Admin",
      "roles": ["ADMIN"],
      "active": true,
      "created_at": "2026-06-01T10:00:00Z"
    }
  ],
  "total": 42,
  "page": 1,
  "page_size": 20
}
```

### 8.4 `PATCH /api/v1/admin/users/{id}/roles`

Headers: `If-Match: 7` (the user's current version, optional).

Request:

```json
{ "roles": ["ADMIN", "CLIENT"] }
```

Response (200):

```json
{
  "id": "uuid",
  "roles": ["ADMIN", "CLIENT"],
  "version": 8
}
```

Errors:

- `400 invalid_role` if a role is not in the enum.
- `403 admin_role_required`.
- `404 user_not_found`.
- `409 cannot_self_demote_last_admin` (with `If-Match` or default optimistic semantics).
- `409 version_conflict` if `If-Match` mismatches.
- `409 cannot_demote_last_admin` (separate error code if the user is the last admin but is being demoted by someone else).

### 8.5 `POST /api/v1/admin/users/{id}/deactivate`

Request: empty body.

Response (200):

```json
{ "id": "uuid", "active": false, "version": 9 }
```

Side effects (transactional): all `refresh_tokens` rows for that user are marked `revoked=true`. The user's `locked` flag is also set to `true` so login attempts return 423.

### 8.6 `POST /api/v1/admin/users/{id}/reactivate`

Request: empty body.

Response (200):

```json
{
  "id": "uuid",
  "active": true,
  "must_change_password": true,
  "version": 10
}
```

### 8.7 `GET /api/v1/admin/audit-log`

Query: `?page=1&page_size=20&action=roles_changed&actor=<uuid>`.

Response (200):

```json
{
  "items": [
    {
      "id": "uuid",
      "actor_user_id": "uuid",
      "actor_email": "ceo@hielo.com",
      "action": "roles_changed",
      "target_user_id": "uuid",
      "target_email": "u@hielo.com",
      "before_json": { "roles": ["CLIENT"] },
      "after_json": { "roles": ["REPARTIDOR"] },
      "request_id": "...",
      "notes": null,
      "created_at": "2026-07-16T10:30:00Z"
    }
  ],
  "total": 17,
  "page": 1,
  "page_size": 20
}
```

## 9. Configuration

New keys in `application.yml`:

```yaml
app:
  admin:
    recover-email: ${ADMIN_RECOVER_EMAIL:}        # CLI override; defaults to empty
    bootstrap-enabled: ${ADMIN_BOOTSTRAP_ENABLED:true}
  invite:
    ttl-hours: 24
    rate-limit:
      capacity: 5
      window-minutes: 60
```

No new third-party libraries. The implementation uses Nimbus JOSE (already a dependency for JWT), Spring Boot 3.x's built-in method security (`@PreAuthorize`), and the JDK's `javax.crypto.Mac` for HMAC.

## 10. Migration sequencing (operational)

The chained PRs below are also the deployment order:

1. PR1 ships; no DB migration. Signup closure is observable in logs and tests.
2. PR2 ships; V6 migration runs (additive). Bootstrap creates the first admin. Operators capture credentials.
3. PR3 ships; JWT shape changes (dual-shape), filter installed. Both services parse both shapes. No client-visible change.
4. PR4 ships; V7 migration runs (additive). Admin endpoints go live. Flutter app gets the new admin console screen via app update.
5. PR5 ships after at least one full deploy cycle of PR4; V8 drops the legacy column and indexes. Final cut-over.

Between PR4 and PR5, the system runs in dual-shape. Operators can monitor token shape via existing JWT log fields (`mvn verify` includes a token-shape assertion test).

## 11. Open design decisions (deferred to sdd-tasks)

These are not blockers but need a decision before `sdd-apply` writes the corresponding task. They are listed here so the tasks phase does not skip them.

1. **The literal Postgres `<app_role>` placeholder** in V7's `REVOKE` statements — confirmed at task time by reading the actual `application.yml` datasource username.
2. **Password blocklist**: do we ship with a small embedded blocklist (top 50 common passwords) for the first slice, or skip it? Recommendation: ship with a 50-word blocklist loaded from `classpath:security/common-passwords.txt`. Decision: SHIP.
3. **`last_login_at`**: not in schema today. Useful for admin list. Add in PR4 by reading from existing `users` table without a new column? No — we cannot derive `last_login_at` without storing it. Decision: DEFER to a follow-up; admin list shows `created_at` only.
4. **Audit log retention**: no automatic cleanup. Mention in ops doc as "monitor and rotate manually".
5. **Invite token revocation UI**: if a stale invite is still pending after expiry, can an admin "purge" it? Yes, in PR4 add `DELETE /api/v1/admin/invites/{id}` that hard-deletes the row (only if `used_at IS NULL AND expires_at < now()`). Document in the task.

## 12. Strict TDD plan per PR

| PR | First RED test (failing) | First GREEN step | Final REFACTOR |
| --- | --- | --- | --- |
| PR1 | `SignupServiceTest.signup_with_role_admin_in_body_creates_cliente()` asserts the row has `roles=[cliente]`, not `roles=[admin]`. | Remove `setRole(User.Role.valueOf(req.role()))` line; force `roles=Set.of(cliente)`. Add hidden-field audit on the request body. | Drop the `role` field from `SignupRequest`; remove the deprecated path from the entity. |
| PR2 | `AdminBootstrapTest.run_on_empty_db_creates_one_admin_with_random_password()` asserts a row exists with `roles=[admin]` and `must_change_password=true`, and a random password was emitted. | Implement `AdminBootstrap.run()` with the query, insert, and `System.out.println`. | Extract generation, password length policy, and bootstrap logic into separate methods. |
| PR3 | `AdminRoleGateFilterTest.request_to_admin_path_with_non_admin_token_returns_403()` and `deactivated_admin_returns_403()`. | Add the filter, wire to chain, query DB. | Extract `RoleRequeryService`, add `AdminContext`. |
| PR4 | One test per new endpoint, each scenario in `specs/admin-roles/spec.md` corresponds to one test method. | Implement each endpoint after its RED. | Extract common audit writing and exception mapping. |
| PR5 | `JwtServiceTest.sign_no_longer_writes_role_claim()` + `parse_rejects_legacy_role_only_token()`. | Remove `role` claim write/read. | Drop the legacy column in V8. |

Each task in `sdd-tasks` MUST produce a `target/test-classes/.../XTest.class` first, then the implementation, then re-run. The `sdd-verify` phase enforces this with the `mvn test` command.

## 13. Chained PR slicing (final recommendation)

```
PR1: admin-roles-signup-closure       ~80-120 lines diff, RED-first
PR2: admin-bootstrap-seeder           ~150-200 lines diff + V6 migration
PR3: admin-roles-gate                 ~250-300 lines diff + JwtService/Filter updates
PR4: admin-roles-management           ~400-500 lines diff + V7 migration (largest)
PR5: admin-roles-cutover              ~80-120 lines diff + V8 migration
```

Total: ~960-1240 lines across 5 PRs. Forecast above 400 lines for PR4 → recommend split further? **No.** PR4 is the cohesive "admin surface" — splitting invites from deactivation would split audit infrastructure awkwardly. Instead, PR4's review should run the full 4R review chain (`review-readability`, `review-reliability`, `review-resilience`, `review-risk`) before merge.

## 14. Risks

1. **Cut-over window mistake** — if PR3 ships but PR5 is forgotten, the legacy `role` column lingers forever. Mitigation: open a tracking issue titled `Remove legacy users.role column` linked from PR3's merge commit. PR5 must merge within 30 days.
2. **Bootstrap credentials leaked in CI logs** — if a CI pipeline boots the service with `ADMIN_BOOTSTRAP_ENABLED=true` against a fresh DB, the random password ends up in CI logs. Mitigation: in CI, set `ADMIN_BOOTSTRAP_ENABLED=false` (env override). Documented in PR2's runbook.
3. **HMAC key reuse** — the invite token key is derived from `JWT_SECRET`. If JWT signing is rotated, the invite key also rotates, invalidating all pending invites. Acceptable for single-tenant. Mitigation: rotate both at the same time; document in ops.
4. **Optimistic lock + admin deactivation race** — admin A demotes admin B at the same moment admin C deactivates admin B. Both transactions commit cleanly because the row was updated; the second commit hits version conflict and returns 409. Documented in API examples.
5. **Flutter hardcoded `/api/v1/admin/**` paths** — if the backend moves admin endpoints to a different service, the client must follow. Mitigation: keep the URL paths stable; the migration is across services, not across URL prefixes.