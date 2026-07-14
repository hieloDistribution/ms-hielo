-- V5 owned by feat/backend-fase1-rest-endpoints
-- Seed delivery_drivers and clients rows for the smoke-test users.
-- The repartidor user gets a delivery_drivers entry; the cliente user gets
-- a clients entry. The user_id values match V5__seed_test_users.sql in
-- sync_db (cross-DB, no FK constraint by design).

INSERT INTO delivery_drivers (id, user_id, display_name, vehicle_type, plate_number, stars, active, created_at, updated_at)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '22222222-2222-2222-2222-222222222222',
    'Repartidor Test',
    'Moto',
    'AAA123',
    5.0,
    true,
    now(),
    now()
)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO clients (id, name, tax_id, address, phone, email, active, latitude, longitude, user_id, created_at, updated_at)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    'Cliente Test',
    '99999999',
    'Av. Test 123, Formosa',
    '+595981000000',
    'cliente@test.com',
    true,
    -26.185,
    -58.174,
    '33333333-3333-3333-3333-333333333333',
    now(),
    now()
)
ON CONFLICT (tax_id) DO NOTHING;