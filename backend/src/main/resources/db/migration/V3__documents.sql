CREATE TABLE documents (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    profile_id          UUID        NOT NULL,
    type                VARCHAR     NOT NULL,
    original_name       VARCHAR     NOT NULL,
    stored_name         VARCHAR     NOT NULL,
    content_type        VARCHAR     NOT NULL,
    size_bytes          BIGINT      NOT NULL,
    sha256              CHAR(64)    NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    identification_type VARCHAR(40),
    certification_name  VARCHAR(160),
    CONSTRAINT pk_documents PRIMARY KEY (id),
    CONSTRAINT fk_documents_profile FOREIGN KEY (profile_id) REFERENCES executive_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_documents_stored_name UNIQUE (stored_name),
    CONSTRAINT ck_documents_type CHECK (type IN ('CV', 'ID', 'CERTIFICATE')),
    CONSTRAINT ck_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_documents_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_documents_profile_id ON documents (profile_id);
CREATE TRIGGER trg_documents_set_updated_at BEFORE UPDATE ON documents FOR EACH ROW EXECUTE FUNCTION set_updated_at();
