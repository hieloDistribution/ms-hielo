-- V1 owned by auth-foundation-sync. No other change may use V1 here.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           varchar(320) UNIQUE NOT NULL,
    password_hash   varchar(72)  NOT NULL,
    vendor_id       uuid         NULL,
    locked          boolean      NOT NULL DEFAULT false,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_family    uuid NOT NULL,
    token_hash      varchar(64) NOT NULL UNIQUE,
    expires_at      timestamptz NOT NULL,
    revoked         boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_refresh_tokens_user_id      ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family       ON refresh_tokens (token_family);
CREATE INDEX ix_refresh_tokens_revoked_exp  ON refresh_tokens (revoked, expires_at);
