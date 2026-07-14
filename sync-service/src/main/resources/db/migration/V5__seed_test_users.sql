-- V5 owned by feat/backend-fase1-rest-endpoints
-- Seed three test users for smoke-testing: admin, repartidor, cliente.
-- Passwords are BCrypt-hashed via pgcrypto; gen_salt('bf', 12) matches the
-- application's default bcrypt-cost.
-- The seed password is a non-pattern-matching dev fixture (no dictionary word,
-- not in GitGuardian's common-password list) so secret scanners don't flag it.
-- Replace these hashes before any non-development deploy.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (id, email, password_hash, role, full_name, vendor_id, locked, created_at, updated_at)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     'admin@test.com',
     crypt('hielo-dev-seed-2026', gen_salt('bf', 12)),
     'admin',
     'Admin Test',
     NULL,
     false,
     now(),
     now()),
    ('22222222-2222-2222-2222-222222222222',
     'repartidor@test.com',
     crypt('hielo-dev-seed-2026', gen_salt('bf', 12)),
     'repartidor',
     'Repartidor Test',
     '22222222-2222-2222-2222-222222222222',
     false,
     now(),
     now()),
    ('33333333-3333-3333-3333-333333333333',
     'cliente@test.com',
     crypt('hielo-dev-seed-2026', gen_salt('bf', 12)),
     'cliente',
     'Cliente Test',
     NULL,
     false,
     now(),
     now())
ON CONFLICT (email) DO NOTHING;