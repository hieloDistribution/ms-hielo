# SDD Explore — `auth-foundation`

**Change:** `auth-foundation`
**Phase:** explore
**Project:** `hielo`
**Working branch:** `feature/auth-foundation`
**Date:** 2026-07-02
**Executor:** sdd-explore (delegated) + parent verification

---

## status

`complete` — exploration written, ready for proposal question round before
`sdd-proposal` runs. No blockers. All inputs read directly by the parent to
verify the subagent's inferences.

---

## executive_summary

`auth-foundation` is the first SDD on a greenfield project. Today the code is
an empty Maven multi-module shell with Spring Boot 3.2.5 + Java 21, two
microservices (`sync-service`, `order-service`) backed by separate PostgreSQL
databases (`sync_db`, `order_db`), and no Spring Security, no user store, no
auth endpoints, and no JWT infrastructure. Auth is intentionally absent from
the PRD; this change lays the transversal security layer that the PRD endpoints
will later sit on top of.

Six product/architecture decisions remain unanswered and must be resolved before
`sdd-proposal` finalises the design: credential store location, token validation
topology, refresh persistence (PG vs Redis), refresh rotation policy, JWT
claims scope, and secret provisioning. Five are surfaced to the user in the
proposal question round that follows this artifact.

---

## verified_inputs (parent-read)

| File | Takeaway |
|---|---|
| `PRD.md` | Mobile-first offline-first order registration. Android WorkManager → `sync-service` → `order-service`. Auth is not in the PRD. Postgres confirmed. |
| `pom.xml` (root) | Maven multi-module with `sync-service` and `order-service`. No parent BOM fields beyond the two modules. |
| `sync-service/pom.xml` | Spring Boot 3.2.5. Java 21. Deps: starter-web, starter-data-jpa, starter-validation, postgresql, starter-test. **No** starter-security, no JWT, no Flyway/Liquibase. |
| `order-service/pom.xml` | Same deps as sync-service. Same gap (no security, no migration framework). |
| `docker-compose.yml` | Single service: `postgres:15-alpine` on port 5432 (`postgres/postgres`). Mounts `./init-scripts` as entrypoint init dir. **No Redis, no other services.** |
| `init-scripts/init.sql` | Creates `sync_db` and `order_db` databases. No table DDL beyond that. |

---

## current_state_findings

- **Maven shell only.** Root pom + two module poms; `src/` trees are empty.
- **Spring Security not on classpath** in either module.
- **No user store.** No `users` table, no JPA entity, no migration framework
  (Flyway/Liquibase) declared.
- **No auth endpoints.** No controllers, no DTOs, no service layer.
- **No JWT infrastructure.** No `JJWT`, no Spring Security OAuth2 Resource
  Server, no Nimbus.
- **No SecurityFilterChain bean.** Defaults will activate if starter-security
  is added without configuration.
- **Two separate PostgreSQL databases.** `sync_db` (for sync-service) and
  `order_db` (for order-service). There is **no shared DB** today. Any cross-
  service DB query must go through HTTP.
- **No Redis in docker-compose.** Refresh-token persistence choices are
  constrained: PG only by default, Redis requires compose changes.

---

## gap_analysis

| Gap | Severity | Notes |
|---|---|---|
| No `spring-boot-starter-security` on either module | Critical | Must be added to both poms in the same change to keep the stack consistent. |
| No user/credential store | Critical | Schema choice (sync_db vs order_db) is a product decision. |
| No JWT library | High | Default to Spring Security OAuth2 Resource Server (already in `spring-boot-starter-security` via `oauth2-resource-server`) — no extra dep. |
| No login endpoint | High | `POST /api/v1/auth/login` — path/contract undecided. |
| No refresh endpoint | High | `POST /api/v1/auth/refresh` — rotation/storage undecided. |
| No SecurityFilterChain | High | Two `@Bean` filter chains, one per service, with consistent policy. |
| No DB migration framework | High | Flyway is the simplest default for Spring Boot 3.x and lives in each module without an extra infra decision. Recommend Flyway. |
| No token secret provisioning strategy | High | Shared HMAC secret must be read from env var, not `application.yml`. |
| No CORS configuration | Medium | Android client is cross-origin; explicit allowlist required. |
| No actuator security | Medium | Health endpoints must be `permitAll`; everything else denied by default once security is on. |
| No logout / token revocation | Medium | Out of scope per parent, but must be acknowledged as deferred. |
| No rate limiting | Low | Out of scope for first slice; document the gap for follow-up. |
| Outbox -> auth context plumbing (future) | Low | Pattern only — outbox already carries a `timestamp` column; future change will add `vendor_id`. No code needed now. |

---

## risks

| ID | Severity | Risk | Mitigation |
|---|---|---|---|
| R-01 | High | Spring Boot 3.x security defaults permit all + form-login on `/login`; CSRF on by default. | Explicit `SecurityFilterChain` per service: stateless, CSRF off, no form-login, no anonymous. |
| R-02 | High | Same JWT signing secret used by sync-service (issuer) and order-service (validator). | Read from `JWT_SECRET` env var; fail fast at startup if absent. Document Vault/K8s path for prod. |
| R-03 | Medium | BCrypt default cost (10) underloads for prod; tests can be slow. | Set cost to 12 via config; keep tests on a smaller (4) override. |
| R-04 | Medium | Clock skew between services can reject short-lived tokens. | `JwtTimestampValidator` with 60 s leeway, configurable. |
| R-05 | High | Refresh token rotation policy undecided; without rotation, stolen refresh tokens stay valid. | Decided in proposal question round (Q4). |
| R-06 | Medium | Logout / revocation deferred — stale sessions remain valid until natural expiry. | Bound by short refresh TTL (e.g., 7 days); tracked as a follow-up change. |
| R-07 | Low | Future outbox messages need `vendor_id` available at write time; if forwarded at dispatch, trust is lost. | Document the pattern; not blocking this change. |
| R-08 | Medium | CORS wildcard on auth endpoints is dangerous. | Explicit allowlist driven by `CORS_ALLOWED_ORIGINS` env var. |
| R-09 | Low | No rate limiting on `/api/v1/auth/login`. | Defer; document gap. |
| R-10 | Medium | Two services share a JWT secret in dev via env var; risk of leaking via logs. | Mask the secret in logback; never print full tokens. |

---

## recommended_proposal_questions

These five product/architecture questions MUST be answered before
`sdd-proposal` finalises. Each is a tradeoff between options, not a request for
factual evidence.

1. **Credential store ownership.** Where do user credentials live?
   a) `users` table in `sync_db` (sync-service owns auth), or
   b) `users` table in `order_db` (order-service owns auth), or
   c) one shared `users` table accessed by both services via a separate
      `auth-service` (out of scope, ruled out for this slice).

2. **Token validation topology.** When `order-service` receives a JWT, does it
   validate locally with the shared HMAC secret (decoupled, fast), or does it
   forward to `sync-service` for validation (centralised trust, extra hop)?

3. **Refresh token persistence.** Stay PostgreSQL-only, or add Redis to
   `docker-compose.yml`?

4. **Refresh token rotation policy.** Single-use (rotated on every refresh,
   old invalidated) or long-lived (valid until expiry, no rotation)?
   Single-use needs concurrent-refresh handling.

5. **JWT claims scope.** Beyond `sub`, must the JWT carry `vendor_id`? Does the
   order-service trust that claim without re-querying the DB?

---

## open_assumptions_until_user_answers

The following are reasonable defaults that sdd-proposal will adopt **unless the
user overrides during the question round**:

- Library: Spring Security 6.x OAuth2 Resource Server (already on classpath
  via `spring-boot-starter-security`); no extra JWT lib needed.
- DB migration: Flyway per module.
- HTTP path: `/api/v1/auth/login`, `/api/v1/auth/refresh` on sync-service.
- Security on actuator: only `/actuator/health` is public; everything else
  denied unless explicitly opened.
- CORS: explicit allowlist driven by env var, locked in `application.yml`.
- Bcrypt cost: 12 (configurable via env).
- Token TTL: access 15 min, refresh 7 days (subject to Q4).
- Secret provisioning: env var `JWT_SECRET` minimum 256 bits; rejected if
  shorter.

---

## next_recommended

Run the **proposal question round** with the 5 questions above against the
user. After answers, launch `sdd-proposal` with the answers inlined so the
proposal can resolve the open assumptions and produce a single, concrete PRD
section for `auth-foundation`.

Do **not** launch `sdd-spec`, `sdd-design`, `sdd-tasks`, or `sdd-apply` until
the user has answered and approved the proposal.

---

## skill_resolution

`skill_resolution: none` — no project/user skill paths injected by the parent.
No coding skill required for the explore phase.

---

## tool_constraints_note

`sdd-explore` reported it had no file-read tools in its runtime and produced
its analysis from parent-provided preflight context alone. The parent then
directly verified every claim against the actual files. No persisted memory
call was attempted because Engram is offline at `127.0.0.1:7437`.
