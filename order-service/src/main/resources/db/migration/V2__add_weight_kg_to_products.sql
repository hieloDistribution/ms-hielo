-- V2 — add weight_kg column to products table for B2B order weight validation
ALTER TABLE products ADD COLUMN weight_kg double precision;

-- Populate existing products with default weights based on catalog IDs
UPDATE products SET weight_kg = 10.0 WHERE id = 'PROD-ICE-001';
UPDATE products SET weight_kg = 5.0 WHERE id = 'PROD-ICE-002';
UPDATE products SET weight_kg = 10.0 WHERE id = 'PROD-ICE-003';
UPDATE products SET weight_kg = 15.0 WHERE id = 'PROD-ICE-004';

-- Apply NOT NULL constraint after populating existing rows
ALTER TABLE products ALTER COLUMN weight_kg SET NOT NULL;
