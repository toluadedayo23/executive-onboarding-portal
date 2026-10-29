CREATE TABLE vetting_cases (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    profile_id             UUID        NOT NULL,
    status                 VARCHAR     NOT NULL,
    assigned_consultant_id UUID,
    more_info_message      TEXT,
    decision_reason        TEXT,
    submitted_at           TIMESTAMPTZ,
    decided_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_vetting_cases PRIMARY KEY (id),
    CONSTRAINT uq_vetting_cases_profile UNIQUE (profile_id),
    CONSTRAINT fk_vetting_cases_profile FOREIGN KEY (profile_id) REFERENCES executive_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_vetting_cases_consultant FOREIGN KEY (assigned_consultant_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_vetting_cases_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'MORE_INFO_REQUIRED', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_vetting_cases_rejection_reason CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL),
    CONSTRAINT ck_vetting_cases_more_info_message CHECK (status <> 'MORE_INFO_REQUIRED' OR more_info_message IS NOT NULL)
);
CREATE INDEX idx_vetting_cases_status_submitted ON vetting_cases (status, submitted_at);
CREATE INDEX idx_vetting_cases_consultant_status ON vetting_cases (assigned_consultant_id, status);
CREATE TRIGGER trg_vetting_cases_set_updated_at BEFORE UPDATE ON vetting_cases FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE vetting_notes (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    case_id    UUID        NOT NULL,
    author_id  UUID        NOT NULL,
    note       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_vetting_notes PRIMARY KEY (id),
    CONSTRAINT fk_vetting_notes_case FOREIGN KEY (case_id) REFERENCES vetting_cases (id) ON DELETE RESTRICT,
    CONSTRAINT fk_vetting_notes_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT
);
COMMENT ON TABLE vetting_notes IS 'Consultant-only; must never be exposed to executives.';
CREATE INDEX idx_vetting_notes_case_id ON vetting_notes (case_id);
CREATE INDEX idx_vetting_notes_author_id ON vetting_notes (author_id);
