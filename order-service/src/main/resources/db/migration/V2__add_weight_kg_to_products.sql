-- V2 — add weight_kg column to products table for B2B order weight validation
ALTER TABLE products ADD COLUMN weight_kg double precision;
