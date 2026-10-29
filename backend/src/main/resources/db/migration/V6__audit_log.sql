CREATE TABLE audit_log (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY,
    actor_id    UUID,
    actor_role  VARCHAR,
    action      VARCHAR     NOT NULL,
    entity_type VARCHAR,
    entity_id   VARCHAR,
    detail      JSONB,
    ip_address  INET,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    prev_hash   CHAR(64)    NOT NULL,
    hash        CHAR(64)    NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_log_role CHECK (actor_role IS NULL OR actor_role IN ('EXECUTIVE', 'CONSULTANT', 'ADMIN')),
    CONSTRAINT ck_audit_log_prev_hash CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_log_hash CHECK (hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_audit_log_hash UNIQUE (hash)
);
COMMENT ON COLUMN audit_log.detail IS 'Must never contain passwords, tokens, national ID values, referee contact details, document contents or other sensitive payloads.';
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_actor_created ON audit_log (actor_id, created_at);

-- Append-only: any update, delete or truncate is refused by the database itself.
CREATE FUNCTION prevent_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit_log is append-only'; END; $$;
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_mutation();
