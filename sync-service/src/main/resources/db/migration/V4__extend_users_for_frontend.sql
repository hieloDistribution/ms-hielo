-- V4 owned by feat/backend-fase1-rest-endpoints
-- Extiende la tabla users con campos que el frontend Flutter necesita para
-- manejar perfiles, roles, avatares y datos comerciales de clientes.
-- Esto reemplaza la dependencia que el frontend tenia contra
-- public.profiles de Supabase.

ALTER TABLE users ADD COLUMN role varchar(20) NOT NULL DEFAULT 'repartidor';
ALTER TABLE users ADD COLUMN full_name varchar(255);
ALTER TABLE users ADD COLUMN avatar_url varchar(500);
ALTER TABLE users ADD COLUMN phone varchar(50);
ALTER TABLE users ADD COLUMN dni varchar(50);
ALTER TABLE users ADD COLUMN business_name varchar(255);
ALTER TABLE users ADD COLUMN business_lat numeric(9,6);
ALTER TABLE users ADD COLUMN business_lng numeric(9,6);
ALTER TABLE users ADD COLUMN business_address varchar(500);
ALTER TABLE users ADD COLUMN last_latitude numeric(9,6);
ALTER TABLE users ADD COLUMN last_longitude numeric(9,6);
ALTER TABLE users ADD COLUMN last_location_updated timestamptz;

ALTER TABLE users ADD CONSTRAINT users_role_chk
    CHECK (role IN ('admin','repartidor','cliente'));

CREATE INDEX idx_users_role ON users(role);