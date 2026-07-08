-- V3 — create_delivery_drivers
CREATE TABLE delivery_drivers (
    id                      uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 uuid          NOT NULL,
    display_name            varchar(255)  NOT NULL,
    vehicle_type            varchar(100),
    plate_number            varchar(50),
    stars                   numeric(3,2)  DEFAULT 5.0,
    last_latitude           numeric(9,6),
    last_longitude          numeric(9,6),
    last_location_updated   timestamptz,
    active                  boolean       NOT NULL DEFAULT true,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX delivery_drivers_user_id_uq ON delivery_drivers (user_id);

-- Alter orders table to add delivery fields
ALTER TABLE orders ADD COLUMN delivery_driver_id uuid REFERENCES delivery_drivers(id) ON DELETE SET NULL;
ALTER TABLE orders ADD COLUMN delivery_latitude numeric(9,6);
ALTER TABLE orders ADD COLUMN delivery_longitude numeric(9,6);
ALTER TABLE orders ADD COLUMN delivery_address varchar(500);
ALTER TABLE orders ADD COLUMN verification_code varchar(10);
ALTER TABLE orders ADD COLUMN status varchar(50) DEFAULT 'PENDING';
ALTER TABLE orders ADD COLUMN accepted_at timestamptz;
ALTER TABLE orders ADD COLUMN delivered_at timestamptz;
