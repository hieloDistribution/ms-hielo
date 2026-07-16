# Runbook — Admin Bootstrap

> Operational procedure for creating the first administrator of the
> `ms-hielo` system, owned by change `admin-console` PR2.
> Audience: the operator deploying `sync-service` against a fresh
> database for the first time. Single-tenant: one company, one `sync_db`,
> one first admin.

## TL;DR

```bash
# 1. Set the JWT secret in the environment (≥32 bytes).
export JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n=')"

# 2. Start sync-service with bootstrap enabled (the default).
java -jar sync-service.jar
# OR via docker compose:
docker compose up sync-service

# 3. Watch stdout for the bootstrap line:
#   [admin-bootstrap] credentials email=admin+<short>@bootstrap.local password=<base64url>
# Capture this line immediately. It will not be shown again.

# 4. Log in via the Flutter app:
#    - email:    the captured email
#    - password: the captured password
#    - The app routes to the password-change screen because the response
#      includes must_change_password=true. Set a real password.

# 5. From the admin console (admin can now issue invites, deactivate,
#    promote / demote), invite any other admins you need.
```

## When this matters

- First-ever boot of `sync-service` against a database that has no
  active admin. After this, the bootstrap becomes a no-op.
- Recovery: if every admin is deactivated or locked out, run with
  `--admin-recover=<email>` (or `ADMIN_RECOVER_EMAIL=<email>` env var)
  to seed a new admin.

## Configuration knobs

| Env var                              | Default                    | Purpose |
| ------------------------------------ | -------------------------- | ------- |
| `JWT_SECRET`                         | empty (refuses to start)   | HS256 secret. ≥32 bytes. Already required by canonical auth spec. |
| `app.admin.bootstrap-enabled`        | `true`                     | Set to `false` in CI / ephemeral test environments so credentials do not appear in build logs. |
| `app.admin.recover-email`            | empty                      | Email of the admin to create during recovery. Only honored when no active admin exists. |
| `APP_ADMIN_BOOTSTRAP_ENABLED`        | `true` (via `app.admin.*`) | Env-var form of the same property. |
| `APP_ADMIN_RECOVER_EMAIL`            | empty                      | Env-var form of `app.admin.recover-email`. |

## What the bootstrap does (and does NOT)

Does:
- On a database with **zero** active admin rows, creates exactly one
  admin row with `roles=[admin]`, `must_change_password=true`,
  `active=true`, `locked=false`.
- Prints the synthetic or recovered email and a random base64url
  password (128 bits of entropy) to **stdout only**.
- Skips silently on subsequent boots (idempotent — boots with an
  existing admin do nothing).

Does NOT:
- Print credentials to logback, to a file, to a remote log shipper, or
  to any HTTP endpoint. stdout is the only sink.
- Persist the cleartext password anywhere. The bcrypt hash is what is
  stored on disk.
- Rotate any other admin's password. Each admin's
  `must_change_password` is set ONLY for the freshly-created row.

## Step-by-step: first boot

1. **Pre-flight.** Ensure `JWT_SECRET` is set to a strong random value
   (at least 32 bytes). The canonical auth spec already refuses to
   start without this. `openssl rand -base64 48` works.

2. **Run sync-service.** Use your normal container / bare-metal
   invocation. Example:

   ```bash
   docker run --rm \
     -e JWT_SECRET="$JWT_SECRET" \
     -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/sync_db \
     -e SPRING_DATASOURCE_USERNAME=sync_app \
     -e SPRING_DATASOURCE_PASSWORD=... \
     hielo-sync-service:1.0.0
   ```

3. **Capture the line.** Within a few seconds of startup, the
   container's stdout (your terminal, `docker logs`, `kubectl logs`)
   emits:

   ```
   [admin-bootstrap] credentials email=admin+a1b2c3d4@bootstrap.local password=ZGVmNDU2Nzg5MGFiY2RlZg capture now, this will not be shown again
   ```

   Save this line into your password manager NOW. If you lose it, the
   only path forward is `ADMIN_RECOVER_EMAIL=<another-email>` on a
   fresh boot.

4. **First login via Flutter.** Open the app, enter the captured email
   and password. The app receives `must_change_password=true` in the
   login response and routes you to the change-password screen BEFORE
   any other screen. Pick a real password (≥12 chars recommended; the
   canonical policy lives in the V7 spec, PR4 will surface a UI
   enforcement).

5. **Issue invites for the rest of the team.** As an admin you can now
   use the admin console to invite other admins. Invites are
   HMAC-SHA256-signed tokens that expire in 24 hours and are
   single-use. See `proposal/proposal.md` and `design/design.md` for
   the invite flow.

## Recovery procedure

If you ever lose access to every admin account (mass deactivation, key
compromise, etc.):

1. Stop the sync-service that runs the production database.

2. Bring up a new sync-service instance pointing at the same database
   but with the recovery email set:

   ```bash
   docker run --rm \
     -e JWT_SECRET="$JWT_SECRET" \
     -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/sync_db \
     -e APP_ADMIN_RECOVER_EMAIL=ceo-new@hielo.com \
     hielo-sync-service:1.0.0
   ```

3. The new instance sees an active admin count of 0 and refuses to
   reuse the existing admin email. It creates a new admin with the
   `ceo-new@hielo.com` email and a random password (printed to
   stdout).

4. Capture the new credentials, log in, change the password, and use
   the admin console to issue invites for whoever else needs access.

5. **Audit the previous incident.** The `admin_audit_log` table
   contains the deactivate / role-change history that led to the
   lockout. Inspect via the admin console (PR4 ships the audit log
   viewer).

## CI / ephemeral environments

CI pipelines that boot sync-service against a fresh database MUST set:

```
app.admin.bootstrap-enabled=false
```

(Or its env-var equivalent `APP_ADMIN_BOOTSTRAP_ENABLED=false`.) This
prevents the random password from landing in CI logs (which are often
retained for months and indexed by log search tools). If you need an
admin in CI for end-to-end tests, seed one explicitly via a test
migration (V5 already seeds three test users including `admin@test.com`
in development).

## What to do if you missed the line

If you lost the credentials printed to stdout:

1. Look at the `audit_log` (or your container platform's logging
   search) for any "[admin-bootstrap]" lines. They are NOT supposed to
   be there — if they are, your logging layer is capturing stdout and
   you have an audit-grade security incident (see `docs/SECURITY_AUDIT.md`).

2. If you cannot find them, run the recovery procedure above with a
   fresh email. The old admin row remains in the database, but you no
   longer have its password; the bootstrap will refuse to overwrite it
   (see `AdminBootstrap.run` "refuse clobber" branch).

3. If the old admin row has no usable password AND you cannot log
   into the database to manually clear it, escalate to engineering.
   Direct SQL UPDATE on the `users.password_hash` column is a
   last-resort operation; document it in your incident log.

## Cross-references

- Spec: `openspec/changes/admin-console/specs/admin-bootstrap/spec.md` (B1, B2, B3).
- Design: `openspec/changes/admin-console/design/design.md` section 5.2.
- Source: `sync-service/src/main/java/com/sales/sync/auth/admin/AdminBootstrap.java`.
- Test: `sync-service/src/test/java/com/sales/sync/auth/admin/AdminBootstrapTest.java`.
- PR-1 audit infrastructure that this PR reuses:
  `sync-service/src/main/java/com/sales/sync/auth/admin/AdminAuditLogger.java`.
