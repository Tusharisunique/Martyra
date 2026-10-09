-- 01_users_roles.sql
-- Who can use the system and what role they have.

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- needed for SHA-256 and UUIDs

CREATE TABLE roles (
    role_id    SERIAL PRIMARY KEY,
    role_name  VARCHAR(50) UNIQUE NOT NULL   -- journalist, lawyer, investigator, admin
);

CREATE TABLE users (
    user_id        SERIAL PRIMARY KEY,
    full_name      VARCHAR(100) NOT NULL,
    email          VARCHAR(150) UNIQUE NOT NULL,
    password_hash  TEXT NOT NULL,
    role_id        INT NOT NULL REFERENCES roles(role_id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
