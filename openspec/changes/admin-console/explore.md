# SDD Explore — admin-console

> Phase result for the `admin-console` change in project `hielo`.
> Authored by the parent orchestrator after a subagent scout detached mid-run;
> context was already loaded from prerequisite files in the same session.

## Status

`success — actionable gaps surfaced, scope revised against current source-of-truth.`

## Executive Summary

The system already has the structural pillars for what the user wants (JWT with a `role` claim, `VendorContext`/`DriverContext`, a Flutter app that calls the Java backend as its single source of truth) but the **admin story has four independent gaps that interact**: the backend has no role-based authorization gate, the signup endpoint accepts a client-supplied `role` (so anyone can self-assign `repartidor`), the only "admin screen" is a read-only operations dashboard, and there is no documented path for the first admin to ever exist. The last point is the deepest blocker and shapes the bootstrap discussion: a CLI seed-on-first-boot plus signed invite tokens is the right default for a single-tenant ice distributor.

The prior change archives (`auth-foundation`, `core-domain-model`) set the bar: their `verify-report`s reached PASS and their specs already define vendor/driver/client identity. `admin-console` must explicitly reference those canonical sections by heading name and add `ROLE`-related requirements on top, never replacing existing `vendor_id` claims or weakening the refresh-token theft detection.

## Context Anchors

Read while scoping this change:

- `/home/hat/projects/ms-hielo/openspec/config.yaml` (now: `current_change=admin-console`, `artifact_store=both`, `working_branch=feature/admin-console`).
- `/home/hat/projects/ms-hielo/openspec/specs/auth/spec.md` (canonical auth contract).
- `/home/hat/projects/ms-hielo/docs/SECURITY_AUDIT.md` (acknowledged — see Risks #1 and #6, partially stale).
- `/home/hat/projects/ms-hielo/PRD.md` (backend) and `/home/hat/projects/UI-HieloPedido/PRD.md` (frontend).
- `/home/hat/projects/ms-hielo/openspec/changes/archive/auth-foundation/{proposal,design,verify-report}.md`.
- `/home/hat/projects/ms-hielo/openspec/changes/archive/core-domain-model/{proposal,design,tasks,apply-progress,verify-report}.md`.
- Live source: `order-service/src/main/java/com/sales/order/auth/security/{JwtService,JwtAuthenticationFilter,SecurityConfig,VendorContext,DriverContext}.java`.
- Live source: `UI-HieloPedido/lib/{main,providers/order_provider,core/{auth/token_storage,network/api_client},screens/{admin,login,register,main_navigation}_screen}.dart`.

## Current State — Backend (ms-hielo)

### Auth and identity

- Two Spring Boot 3 services (`sync-service` identity issuer, `order-service` resource server). Both refuse to start if `JWT_SECRET` is missing or shorter than 32 bytes (canonical auth spec).
- JWT carries `sub` (UUID user_id), an **optional** `vendor_id`, an `iss` matching the configured issuer, an `aud` containing `hielo-order`, and a **single-string** `role` claim — already parsed by `JwtService.ParsedToken(userId, vendorId, role)`.
- Refresh tokens sit behind a token-family rotation model with theft-detection replay burn. Treat this as immutable for this change.
- `order-service` exposes `VendorContext.exposeVendorId()` and a `DriverContext` for the current principal. **There is no `AdminContext`.** Today the role is read but never enforced.

### Authorization

- `SecurityConfig.java` is `anyRequest().authenticated()` with no role gate. There are no `@PreAuthorize` annotations. A client who has *any* valid access token can call every endpoint.
- `JwtAuthenticationFilter` populates a generic principal and delegates to the configured authentication entry point on 401 (`{"error":"token_expired"}`).
- CORS allows every origin (`setAllowedOriginPatterns(List.of("*"))`). Acceptable for a localhost dev tunnel; must be tightened before any real deployment, but is **out-of-scope for `admin-console`**.

### Domain model and persistence

- Entities already on disk: `Vendor`, `Client`, `Order`, `OrderItem`, `Product`, `DeliveryDriver`, `DriverReview`, `VendorClientAssignment`, plus the auth tables (`users`, `refresh_tokens`, `processed_requests`).
- There is **no `users.role` column nor a `user_roles` join table** that the codebase reads today; the `role` claim is whatever the JWT issuer decided to write at login.
- Sync-service exposes `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/signup` (the username+password email flow that the Flutter client uses).
- Order-service exposes `/api/v1/orders/**` and `/api/v1/directory/**` (clients, drivers). No `/api/v1/admin/**` controller exists.

## Current State — Frontend (UI-HieloPedido)

### Routing and auth flow

- `main.dart` boots into either `WelcomeScreen`/`LoginScreen` (anonymous) or `MainNavigationScreen` (authenticated).
- `TokenStorage` persists the JWT pair and a cached profile snapshot (`user_id`, `email`, `role`, `full_name`, `avatar_url`) in platform-encrypted storage (Keychain / EncryptedSharedPreferences).
- `OrderProvider` is documented as the **single source of truth, no Supabase remains**: `ApiClient` injects the JWT into every call, with one-shot refresh-token rotation on 401, against `sync-service:8081` and `order-service:8082`. A grep for `supabase_flutter` returns zero matches in `pubspec.yaml`; in `lib/` only legacy comments mention Supabase. **The `SECURITY_AUDIT.md` bypass claim is no longer accurate for the primary data path** — see Risk #6.

### Admin screen as it exists today

- `AdminScreen` has four choice-chip tabs (`Resumen`, `Logística`, `Auditorías`, `Despachos`). It is a **read-only operations dashboard**: revenue metric, route volume metric, `AdminMapView` embed, repartidor-online audit list, despachos history list.
- It does **not** list users, does **not** create users, does **not** edit roles, does **not** invite admins. Calling it "admin console" today is a misnomer.

### Register screen — the live authorization bypass

- `RegisterScreen` lets an anonymous visitor pick `Preventista` or `Cliente`, fill the form, and `POST /api/v1/auth/signup` with a `role` field of their choosing. The body of `signUpWithEmail` in `OrderProvider` literally passes the client's selected `role` to the API.
- Combined with the backend accepting that `role`, this means: **anyone can self-assign `repartidor` without an admin's blessing**. The only thing keeping the system safe today is that nobody ships a fixed admin promotion function — `repartidor` is the highest privilege anyone has access to right now.

### What is missing on the UI side

- A user directory screen with search/filter by role.
- A "create invite" form for a new admin (email + initial role + expiry), surfacing a one-time `invite_url`.
- A "change role" action per user, with audit-friendly metadata (who changed what, when).
- A "deactivate / suspend" action (replaces hard delete).
- A confirmation and audit-trail surface (every admin writeable action should be in the screen with a small inline audit log).

## Gap Inventory

| # | Gap | Where it hurts | Why it blocks admin/RBAC |
| --- | --- | --- | --- |
| G1 | No `AdminContext` and no role-gated controllers in `order-service`. | Any authenticated user can call any endpoint. | Cannot enforce "only admins may create / change roles" even after we build the endpoint. |
| G2 | `JwtService.ParsedToken.role` is a single string; no separation between `VENDOR`, `DRIVER`, `CLIENT`, `ADMIN`. | JWT cannot express multi-role users cleanly. | Bootstrap, invite, and audit flows all want a list (or a join table) — single string forces backflips. |
| G3 | `/api/v1/auth/signup` accepts `role` from the client and trusts it. | Anyone can self-register as `repartidor` (today) or as any role we add later. | Breaks the entire admin model; first thing to close before promoting anyone to admin. |
| G4 | No first-admin bootstrap path. | There is no admin to invite anyone to be admin. | Chicken-and-egg. Must be solved before G1 has any user-facing value. |
| G5 | `AdminScreen` is a read-only metrics view. | Operators cannot manage users or roles from the app. | Even after fixing G1–G4, the user-facing capability still does not exist. |
| G6 | `register_screen.dart` allows self-picking `Preventista`. | Self-promotion to a delivery role at will. | Mirrors G3 on the client side; tied to the same fix. |
| G7 | `SECURITY_AUDIT.md` is stale; no clear current threat model. | Reviewers and future agents cannot tell what's still open. | Hygiene; we should produce an updated summary in the design phase. |

## Bootstrap of the First Admin — Options

Single-tenant ice distributor. The threat model is: **a single enterprise with a handful of admins, one main office, no public signups.** That narrows the options meaningfully.

### Option A — CLI seeder + signed invite tokens (recommended)

- `sync-service` ships a `CommandLineRunner` (or a `java -jar sync-service.jar --bootstrap-admin=…`) guarded by `if (adminCount() == 0)`. On first boot only, it generates a strong random password, writes a `users` row with role `ADMIN`, a forced `must_change_password=true` flag, and prints the credentials to the **console** (never to logs, never to an API response).
- Subsequent admins are created through a flow that requires an existing admin to mint a signed invite token (HMAC-SHA256 over `{email, role, exp, jti}`, secret = the `JWT_SECRET` plus a server-wide admin-invite salt). The token arrives out-of-band (email/WhatsApp), and the recipient redeems it at `POST /api/v1/admin/invites/redeem` by setting their initial password. The token is single-use and short-lived (24h by default).
- **Holdings:** whoever can boot the sync-service has root. That is acceptable for the operator (one company, internal) but must be paired with audit logging.
- **Attack surface:** brute-force of invite tokens is bounded by exp+jti+single-use. Bootstrap credentials travel through operator terminal only.
- **Trade-off:** requires operational discipline — the printed credential must be captured immediately and rotated.

### Option B — Hardcoded env-var super admin

- `super_admin_email` + `super_admin_password` env vars; the service seeds the row on boot.
- **Disadvantage:** env-var passwords show up in shell history, docker compose files, CI logs. Not recommended even for a small company.

### Option C — External IdP (Google Workspace, Auth0, Keycloak)

- Real RBAC offloaded to an OIDC provider.
- **Disadvantage:** hosting and operational cost is unjustified for a single company on a single product. Out-of-scope unless the company asks for SSO specifically.

### Option D — SQL seed migration with a fixed bcrypt password hash

- Migration inserts an admin row with a fixed bcrypt hash that the operator knows.
- **Disadvantage:** the password is publicly knowable (or at least knowable to anyone who can read the repo). The audit trail is "we shipped the password in git." Not acceptable.

**Recommendation: Option A.** It is the only one that gives the operator sole custody of the first credential, supports safe bootstrapping of additional admins via the application itself, and does not leak credentials through the codebase.

## Risks and Open Questions

1. **(CRITICAL) Signup allows client-chosen `role`.** Before any other admin work, the `/api/v1/auth/signup` payload must drop `role` and the backend must default new users to a `CLIENT` role; admin/repartidor promotion happens only through authenticated admin-gated endpoints. Strict TDD: RED → GREEN → TRIANGULATE → REFACTOR on the signup payload shape.
2. **(HIGH) No authorization gate.** A new `RoleAuthorizationFilter` (or per-controller `@PreAuthorize`) is required before any `/api/v1/admin/**` endpoint is reachable. The canonical auth spec already requires "informational only" treatment of `vendor_id`; the `role` claim will need a parallel invariant that says the role claim is **advisory** for display and **authoritative for token issuance**, and **never authoritative for granting elevated permissions without an `ADMIN` role membership lookup** at the controller level.
3. **(HIGH) No first-admin path.** Without Option A from the bootstrap discussion, the system has no admins. This is a hard prerequisite for any test that involves assigning roles.
4. **(MEDIUM) `role` claim is a single string.** Promoting to `roles` (array) means every `JwtService`-consuming site needs to know about the new shape. Carry the old single-string behaviour for backwards compatibility within this change's scope (dual-read) but only if the canonical auth spec allows. Reference the canonical auth spec to confirm before extending.
5. **(MEDIUM) Audit trail.** Every admin write must be logged (who, what, target user, before/after roles, timestamp, request id). Decide storage: database table vs external log. Recommend a `admin_audit_log` table inside Postgres for the first slice.
6. **(LOW, hygiene) `SECURITY_AUDIT.md` is stale.** The document talks about Flutter→Supabase bypass; the code has moved past that for the primary data path. Either update or supersede it. Decide whether to do that in this change or in a follow-up.
7. **(LOW) Offline-first `signUpWithEmail`** in the Flutter provider — the signup call goes through `ApiClient`, which is online-only. If the operator wants admins to invite from a tablet on a flaky network, this change should think about offline invite storage. Probably defer to a follow-up.
8. **(QUESTION) Should the email-and-password flow stay or migrate to magic links / OIDC?** Magic links are friendlier but add an email send step (a new dependency). Defer to proposal question round.

## Proposed Scoping for the Next Phase (`sdd-proposal`)

The next phase should produce a proposal that:

- Restates the **business problem** in the user's terms: who is the admin, what they need to do, what today's admin screen does not give them.
- States **first-slice scope**: hard-fence the Supabase-less app's admin story — bootstrap the first admin, assign admin/repartidor roles, list and deactivate users. Catalogue and order management are explicitly out-of-scope for the first slice.
- Calls out the **non-goals** for this change: no IdP integration, no repartidor GPS tracking changes, no offline admin invites, no marketing/analytics surfaces.
- Names the **target users and situations**: one operator running the company, two-to-five admin users, possibly an office admin vs. an operations admin.
- Lists **business rules**: who can assign roles, whether an admin can self-demote, what happens when an admin is deactivated, whether repartidor can be a multi-role user.
- Surfaces **edge cases** the user might want to weigh in on: lost admin (no one left can promote), two admins race-promote the same user, repartidor promoted to admin while on shift.
- Asks about **product tradeoffs**: invite link vs in-person password reset for the second admin, audit log UI vs raw export, deactivation vs hard-delete semantics.

## Artifact list

- This file: `/home/hat/projects/ms-hielo/openspec/changes/admin-console/explore.md`.
- Engram topic key: `sdd/admin-console/explore` (to be persisted in the same change ticket by the parent).
- Updated `openspec/config.yaml`: `current_change=admin-console`, `artifact_store=both`, `working_branch=feature/admin-console`.

## Notes for the next phase

- Hard-prerequisite before any proposal/spec write: a concrete answer on the bootstrap option (A is recommended but the user may have operational constraints that change the calculation).
- Hard-prerequisite before any `sdd-apply` work: the canonical auth spec must be re-read at the start of each subsequent phase. Any role-related requirement added by `admin-console` MUST reference the canonical auth spec section heading rather than redefining tokens.
- Subagent scout history: the `sdd-explore` user-agent run detached mid-flight on the very first invocation (it tried to read files via intercom instead of `read`/`bash`). Parent took over and authored this artifact. If a future phase needs fresh-context recon, prefer the `scout` (builtin) agent or pass an explicit `cwd: /home/hat/projects/ms-hielo` to the user-level `sdd-explore`.
