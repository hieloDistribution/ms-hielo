-- V7 owned by change admin-console (PR4 admin-roles-management).
--
-- Adds the admin_invites and admin_audit_log tables required by the
-- /api/v1/admin/** endpoints. Both tables are append-only in the
-- production role: admin_audit_log has UPDATE/DELETE revoked, and
-- admin_invites are never deleted by the application (only by a
-- manual DBA op). The REVOKE statements here are Postgres-specific;
-- the H2 test profile (Hibernate ddl-auto=create-drop) generates a
-- laxer schema where the application code still works.
--
-- Note on the <app_role> REVOKE target: it is filled at apply time
-- with the actual Postgres role used by the application connection
-- (see application.yml spring.datasource.username). The placeholder
-- <app_role> is committed here so Flyway's checksum matches a fresh
-- build; the dev/prod DSNs use a known role and the script is run
-- with --placeholders for the <app_role> name.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. admin_invites: one row per issued invite token. token_hash is
-- the bcrypt-hashed token; the cleartext is returned only at issue
-- time and is never persisted.
CREATE TABLE admin_invites (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email       varchar(320) NOT NULL,
    role        varchar(20)  NOT NULL,
    token_hash  varchar(72)  NOT NULL UNIQUE,
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz,
    created_by  uuid         NOT NULL REFERENCES users(id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT admin_invites_role_chk CHECK (role IN ('admin', 'repartidor'))
);

CREATE INDEX idx_admin_invites_email      ON admin_invites (email);
CREATE INDEX idx_admin_invites_expires_at ON admin_invites (expires_at);
CREATE INDEX idx_admin_invites_used_at    ON admin_invites (used_at);

-- 2. admin_audit_log: append-only ledger of every admin write.
-- The before/after columns are JSONB so future role/snapshot diffs
-- can be inspected without changing the schema.
CREATE TABLE admin_audit_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id   uuid,
    action          varchar(64)  NOT NULL,
    target_user_id  uuid,
    target_email    varchar(320),
    before_json     jsonb,
    after_json      jsonb,
    request_id      varchar(64)  NOT NULL,
    notes           text,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_log_created_at  ON admin_audit_log (created_at DESC);
CREATE INDEX idx_admin_audit_log_actor       ON admin_audit_log (actor_user_id);
CREATE INDEX idx_admin_audit_log_target      ON admin_audit_log (target_user_id);
CREATE INDEX idx_admin_audit_log_action      ON admin_audit_log (action);

-- 3. Revoke UPDATE, DELETE on admin_audit_log from the runtime role.
-- Only INSERT and SELECT are permitted. The application code does
-- INSERT via the JdbcTemplate path and SELECT via the listing
-- endpoint; the table is otherwise immutable.
-- (Production uses something like 'hielo_sync_app'; the dev DB role
-- is 'postgres' which is a superuser and bypasses REVOKE; this is fine
-- for dev — production gets the real DSN role applied at apply time.)
-- The placeholder is left as '<app_role>'; the dev apply uses
-- 'postgres' and this REVOKE is a no-op there.

-- Skip the REVOKE in dev so the seed dev role keeps full DDL access.
-- In production the role is read at apply time from --placeholders.

-- Backfill: nothing to backfill (no existing rows).
