-- V7 owned by integration/v2-with-admin-agenda
-- Re-seed repartidor (deleted in V6 when 'vendedor' role was added) and
-- add a 'vendedor' user for the preventista/admin-assign-agenda flow.
-- Idempotent via ON CONFLICT DO NOTHING so Flyway can re-run safely.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (id, email, password_hash, role, full_name, vendor_id, locked, created_at, updated_at)
VALUES
    ('44444444-4444-4444-4444-444444444444',
     'repartidor@test.com',
     crypt('hielo-dev-seed-2026', gen_salt('bf', 12)),
     'repartidor',
     'Repartidor Test',
     NULL,
     false,
     now(),
     now()),
    ('55555555-5555-5555-5555-555555555555',
     'vendedor@test.com',
     crypt('hielo-dev-seed-2026', gen_salt('bf', 12)),
     'vendedor',
     'Vendedor Test',
     '55555555-5555-5555-5555-555555555555',
     false,
     now(),
     now())
ON CONFLICT (email) DO NOTHING;