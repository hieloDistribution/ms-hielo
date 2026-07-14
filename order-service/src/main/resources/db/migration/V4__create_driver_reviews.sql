-- V4 owned by feat/backend-fase1-rest-endpoints
-- Tabla de reseñas/calificaciones de repartidores. Reemplaza la dependencia
-- que el frontend tenia contra public.repartidor_details.stars de Supabase.
-- Una reseña por pedido, rating 1-5.
-- Tambien agrega clients.user_id para resolver /api/v1/clients/me.

CREATE TABLE driver_reviews (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id   uuid NOT NULL REFERENCES delivery_drivers(id) ON DELETE CASCADE,
    order_id    varchar(36) NOT NULL REFERENCES orders(client_order_id) ON DELETE CASCADE,
    rating      smallint NOT NULL,
    comment     text,
    created_by  uuid NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT driver_reviews_rating_chk CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT driver_reviews_order_uq UNIQUE (order_id)
);

CREATE INDEX idx_driver_reviews_driver_id ON driver_reviews(driver_id);
CREATE INDEX idx_driver_reviews_created_at ON driver_reviews(created_at DESC);

-- Link clients <-> users (cross-DB, no FK constraint).
ALTER TABLE clients ADD COLUMN user_id uuid;
CREATE UNIQUE INDEX clients_user_id_active_uq ON clients(user_id)
    WHERE user_id IS NOT NULL AND deleted_at IS NULL;