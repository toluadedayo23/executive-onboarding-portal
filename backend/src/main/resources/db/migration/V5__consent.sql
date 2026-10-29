CREATE TABLE consents (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL,
    ip_address   INET,
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_consents PRIMARY KEY (id),
    CONSTRAINT fk_consents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_consents_withdrawal CHECK (withdrawn_at IS NULL OR withdrawn_at >= granted_at),
    CONSTRAINT uq_consents_active_user UNIQUE (user_id, withdrawn_at)
);
COMMENT ON TABLE consents IS 'A row means consent was given; withdrawal sets withdrawn_at and never deletes the row. Re-consent creates a new row.';
CREATE INDEX idx_consents_user_id ON consents (user_id);
CREATE TRIGGER trg_consents_set_updated_at BEFORE UPDATE ON consents FOR EACH ROW EXECUTE FUNCTION set_updated_at();
