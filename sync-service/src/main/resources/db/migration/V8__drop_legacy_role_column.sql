-- V8 owned by change admin-console (PR5 admin-roles-cutover).
--
-- Final cut-over (admin-console PR5). Drops the legacy single-string
-- `users.role` column that PR2 kept around as a back-compat for the
-- cut-over window. The multi-role `users.roles` collection (via
-- `user_roles` join table) is the source of truth from V6 onwards.
--
-- After this migration runs:
--   * sign() in JwtService writes only the `roles` array claim
--     (no more `role` single-string).
--   * parse() in JwtService accepts only the `roles` array (no
--     fallback to the legacy `role` string).
--   * `getStringClaim("role")` calls in production code are blocked
--     by the PR5 CI grep-gate.
--
-- V8 is safe to run while the system is live: existing tokens
-- (already issued with `role: "admin"`) will fail to parse after
-- this migration + the V8 grep-gate, but those tokens are short-lived
-- (15-minute TTL) and clients re-authenticate automatically. The
-- refresh-token rotation handles the rest.

ALTER TABLE users DROP COLUMN IF EXISTS role;
DROP INDEX IF EXISTS idx_users_role;
