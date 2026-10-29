CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE FUNCTION set_updated_at() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END; $$;

CREATE TABLE users (
    id                 UUID        NOT NULL DEFAULT gen_random_uuid(),
    email              VARCHAR     NOT NULL,
    password_hash      VARCHAR     NOT NULL,
    role               VARCHAR     NOT NULL,
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    failed_login_count INTEGER     NOT NULL DEFAULT 0,
    locked_until       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    token_version      INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT ck_users_role CHECK (role IN ('EXECUTIVE', 'CONSULTANT', 'ADMIN')),
    CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0),
    CONSTRAINT ck_users_token_version_nonneg CHECK (token_version >= 0)
);
COMMENT ON COLUMN users.token_version IS 'Incremented to revoke all existing tokens on logout, deactivation, or a future password change.';

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE INDEX idx_users_locked_until ON users (locked_until);
CREATE TRIGGER trg_users_set_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
