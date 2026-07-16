-- Drop the old role check constraint
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_chk;

-- Add new constraint with 'vendedor'
ALTER TABLE users ADD CONSTRAINT users_role_chk
    CHECK (role IN ('admin', 'vendedor', 'repartidor', 'cliente'));

-- Delete active refresh tokens for the test driver user
DELETE FROM refresh_tokens WHERE user_id = '22222222-2222-2222-2222-222222222222';

-- Delete the seed driver user
DELETE FROM users WHERE email = 'repartidor@test.com';
