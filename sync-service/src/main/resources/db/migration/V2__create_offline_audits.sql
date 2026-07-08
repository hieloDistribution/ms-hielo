-- V2 — create_offline_audits
CREATE TABLE offline_audits (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    repartidor_id     uuid         NOT NULL,
    disconnected_at   timestamptz  NOT NULL,
    reconnected_at    timestamptz  NOT NULL,
    duration_minutes  integer      NOT NULL
);

CREATE INDEX ix_offline_audits_repartidor ON offline_audits (repartidor_id);
