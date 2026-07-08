-- V1 — core-domain-model PR-1
-- Creates the clients, vendors and vendor_client_assignments tables in
-- order_db. The legacy orders / order_items / products tables remain
-- pre-Flyway (baseline-version=0); re-bringing them under Flyway is a
-- follow-up SDD (order-fk-migration).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Crear tablas legado si no existen (necesario al iniciar en una BD vacía como Supabase)
CREATE TABLE IF NOT EXISTS products (
    id          varchar(50)   PRIMARY KEY,
    name        varchar(255)  NOT NULL,
    price       numeric(10,2) NOT NULL,
    stock       int4          NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
    client_order_id   varchar(36)   PRIMARY KEY,
    client_id         varchar(50)   NOT NULL,
    salesperson_id    varchar(50)   NOT NULL,
    created_at        timestamp     NOT NULL,
    total_amount      numeric(12,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS order_items (
    id                bigserial     PRIMARY KEY,
    client_order_id   varchar(36)   NOT NULL REFERENCES orders(client_order_id) ON DELETE CASCADE,
    product_id        varchar(50)   NOT NULL,
    quantity          int4          NOT NULL,
    price             numeric(10,2) NOT NULL
);

-- Insertar productos semilla si no existen
INSERT INTO products (id, name, price, stock) VALUES
('PROD-ICE-001', 'Hielo Rolito 2kg', 150.00, 1000),
('PROD-ICE-002', 'Hielo Rolito 5kg', 300.00, 500),
('PROD-ICE-003', 'Hielo Rolito 10kg', 550.00, 300),
('PROD-ICE-004', 'Hielo Escama 15kg', 750.00, 200)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE clients (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(255)  NOT NULL,
    tax_id      varchar(50)   NOT NULL,
    address     varchar(500)  NOT NULL,
    phone       varchar(50)   NOT NULL,
    email       varchar(320),
    active      boolean       NOT NULL DEFAULT true,
    deleted_at  timestamptz,
    latitude    numeric(9,6),
    longitude   numeric(9,6),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_by  uuid,
    CONSTRAINT clients_tax_id_uq UNIQUE (tax_id)
);
CREATE INDEX idx_clients_deleted_at_null ON clients (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_clients_tax_id          ON clients (tax_id);

CREATE TABLE vendors (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid          NOT NULL,                  -- No FK (cross-DB).
    display_name    varchar(255)  NOT NULL,
    employee_code   varchar(50),
    phone           varchar(50),
    email           varchar(320),
    active          boolean       NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    latitude        numeric(9,6),
    longitude       numeric(9,6),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid
    -- No FK on user_id: see the Cross-database architecture proposal section.
);
CREATE UNIQUE INDEX vendors_user_id_uq           ON vendors (user_id);
CREATE        INDEX idx_vendors_deleted_at_null ON vendors (deleted_at) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_vendors_employee_code_active
                                                ON vendors (employee_code) WHERE employee_code IS NOT NULL;

CREATE TABLE vendor_client_assignments (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       uuid          NOT NULL REFERENCES vendors(id),
    client_id       uuid          NOT NULL REFERENCES clients(id),
    effective_from  timestamptz,
    effective_to    timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_by      uuid,
    CONSTRAINT vca_effective_interval_chk CHECK (
        effective_to IS NULL
        OR effective_from IS NULL
        OR effective_to >= effective_from
    )
);
CREATE UNIQUE INDEX vca_vendor_client_active_uq
    ON vendor_client_assignments (vendor_id, client_id)
    WHERE effective_to IS NULL;
CREATE INDEX idx_vca_vendor_id ON vendor_client_assignments (vendor_id);
CREATE INDEX idx_vca_client_id ON vendor_client_assignments (client_id);