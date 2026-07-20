-- V7 owned by integration/v2-with-admin-agenda
-- Pair the repartidor + vendedor users from sync_db (V7__seed_repartidor_and_vendedor.sql)
-- with their order_db entries. Idempotent so Flyway can re-run safely.

INSERT INTO delivery_drivers (id, user_id, display_name, vehicle_type, plate_number, stars, active, created_at, updated_at)
VALUES (
    '44444444-4444-4444-4444-444444444444',
    '44444444-4444-4444-4444-444444444444',
    'Repartidor Test',
    'Moto',
    'AAA123',
    5.0,
    true,
    now(),
    now()
)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO vendors (id, user_id, display_name, employee_code, phone, email, active, created_at, updated_at)
VALUES (
    '55555555-5555-5555-5555-555555555555',
    '55555555-5555-5555-5555-555555555555',
    'Vendedor Test',
    'VEND-001',
    '+595981000000',
    'vendedor@test.com',
    true,
    now(),
    now()
)
ON CONFLICT (user_id) DO NOTHING;