-- Insert-only (rows are purged, never updated), so there is no updated_at.
CREATE TABLE idempotency_keys (
    user_id         UUID        NOT NULL,
    idempotency_key UUID        NOT NULL,
    request_hmac    CHAR(64)    NOT NULL,
    response_status SMALLINT    NOT NULL,
    response_body   JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_idempotency_keys_hmac CHECK (request_hmac ~ '^[0-9a-f]{64}$')
);
COMMENT ON COLUMN idempotency_keys.request_hmac IS 'HMAC-SHA256 of the canonical request, keyed with a subkey of ENCRYPTION_KEY. Never a plain hash: bodies can hold low-entropy sensitive values.';
COMMENT ON COLUMN idempotency_keys.response_body IS 'Only {version, currentStep, stepStatus}; never draft data or sensitive values.';
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
