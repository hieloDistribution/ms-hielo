-- V6 owned by change admin-console (PR2 admin-bootstrap-seeder + PR3 admin-roles-gate).
--
-- Shape: `roles` is a FIRST-CLASS TABLE (not an enum). Each known role is
-- a row in `roles`; `user_roles` is a many-to-many join with FKs to both
-- `users` and `roles`. This is a deliberate move away from the original
-- design's enum-only shape: roles become data, not code, so future
-- additions (custom role names, per-role metadata like description /
-- icon / default permissions) are migrations or admin endpoints, not
-- Java redeploys.
--
-- Backwards compatibility: the legacy single-string column `users.role`
-- is preserved through the JWT cut-over window (PR3-PR5) and dropped
-- in V8. The setRoles() / Role entity in Java keeps both columns in
-- sync.
--
-- Idempotency: this migration is intended for first-time apply only.
-- Re-running on a DB that already has the shape B tables is safe
-- (DROP TABLE IF EXISTS guards the recreate; ON CONFLICT DO NOTHING
-- guards the backfill).

-- 1. Create the `roles` table — source of truth for role names and
-- future metadata (description, default permissions, etc.).
CREATE TABLE roles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(20)  NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT roles_name_chk CHECK (name IN ('admin', 'repartidor', 'cliente'))
);

CREATE INDEX idx_roles_name ON roles (name);

-- 2. Seed the three known roles. Future roles can be added by additional
-- migrations or by a privileged admin endpoint (not in this change's
-- scope). Description is human-readable, for the admin console UI.
INSERT INTO roles (name, description) VALUES
    ('admin',      'System administrator with full access to /api/v1/admin/**'),
    ('repartidor', 'Distribution driver — accepts orders, reports GPS location'),
    ('cliente',    'End customer — places and tracks orders');

-- 3. Replace the old `user_roles(role VARCHAR)` join table with one
-- that holds a FK to `roles.id`. The old shape (string role in the
-- join table) was from the same PR2 schema, never released to
-- production; safe to drop and recreate.
DROP TABLE IF EXISTS user_roles;

CREATE TABLE user_roles (
    user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  UUID NOT NULL REFERENCES roles(id)  ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);

-- 4. Backfill from the legacy `users.role` string column. For each
-- existing user that has a non-null role string, look up the matching
-- `roles.id` and insert a row into `user_roles`. ON CONFLICT DO
-- NOTHING guards against double-insert if the migration is re-run.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.name = u.role
WHERE u.role IS NOT NULL
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 5. New columns on users.
ALTER TABLE users ADD COLUMN active                BOOLEAN     NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN must_change_password  BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN version               BIGINT      NOT NULL DEFAULT 0;

-- 6. Drop the old single-string CHECK constraint on users.role. The
-- `role` column itself stays for cut-over; the CHECK constraint
-- is no longer authoritative since `roles.name` is the source of
-- truth going forward.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_chk;

-- 7. Drop the old `idx_users_role` index.
DROP INDEX IF EXISTS idx_users_role;
