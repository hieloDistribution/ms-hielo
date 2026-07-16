-- V6 owned by change admin-console (PR2 admin-bootstrap-seeder + PR3 admin-roles-gate).
--
-- Adds multi-role support, soft-delete / activation flags, and optimistic
-- locking for the role-mutation surface.
--
-- Note on schema shape: this migration creates a join table
-- `user_roles(user_id, role)` instead of a Postgres-only `users.roles TEXT[]`
-- column. The design originally specified the array form, but the H2 test
-- profile used by sync-service (`application-test.yml`) does NOT support
-- `TEXT[]`. The join-table form is fully portable (Postgres + H2) and gives
-- the same `roles` multi-value semantics from the entity's perspective.
-- The design's intent (multi-role per user, GIN-indexable lookups) is
-- preserved: the `idx_user_roles_role` index serves the same `WHERE role = ?`
-- queries that the GIN index on `users.roles` would have served. PR3's
-- `RoleGateFilter` uses `idx_user_roles_role` instead of a GIN index.
--
-- The legacy `users.role` single-string column is preserved for the JWT
-- cut-over window (PR3-PR5). It is dropped in V8 (PR5).

-- 1. New join table for multi-role support.
CREATE TABLE user_roles (
    user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(20)  NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT user_roles_role_chk CHECK (role IN ('admin', 'repartidor', 'cliente'))
);

CREATE INDEX idx_user_roles_role ON user_roles (role);

-- 2. Backfill user_roles from the existing users.role column for all
-- existing rows. ON CONFLICT DO NOTHING guards against double-insert if
-- the migration is re-run after a partial backfill.
INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users
ON CONFLICT (user_id, role) DO NOTHING;

-- 3. New columns on users.
ALTER TABLE users ADD COLUMN active                BOOLEAN     NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN must_change_password  BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN version               BIGINT      NOT NULL DEFAULT 0;

-- 4. Drop the old single-string CHECK constraint on users.role. The
-- `role` column itself stays for cut-over; the CHECK constraint
-- is no longer authoritative since `user_roles.role` is the source
-- of truth going forward.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_chk;

-- 5. Drop the old `idx_users_role` index since the array-of-roles
-- lookup now goes through `idx_user_roles_role`. Postgres-only.
DROP INDEX IF EXISTS idx_users_role;
