CREATE TABLE executive_profiles (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id               UUID        NOT NULL,
    draft_data            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version               INTEGER     NOT NULL DEFAULT 1,
    current_step          INTEGER,
    submitted_at          TIMESTAMPTZ,
    national_id_encrypted TEXT,
    national_id_last4     VARCHAR(4),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_executive_profiles PRIMARY KEY (id),
    CONSTRAINT uq_executive_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_executive_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_executive_profiles_version CHECK (version >= 1)
);
COMMENT ON COLUMN executive_profiles.national_id_encrypted IS 'Encrypted by the application with AES-256-GCM.';
COMMENT ON COLUMN executive_profiles.version IS 'The application increments version for optimistic locking; the database does not auto-increment it.';
CREATE TRIGGER trg_executive_profiles_set_updated_at BEFORE UPDATE ON executive_profiles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The tables below are filled from the draft at submission; they have no meaning without the profile.
CREATE TABLE experiences (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    profile_id   UUID        NOT NULL,
    type         VARCHAR     NOT NULL,
    organisation VARCHAR     NOT NULL,
    title        VARCHAR     NOT NULL,
    start_date   DATE        NOT NULL,
    end_date     DATE,
    description  TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_experiences PRIMARY KEY (id),
    CONSTRAINT fk_experiences_profile FOREIGN KEY (profile_id) REFERENCES executive_profiles (id) ON DELETE CASCADE,
    CONSTRAINT ck_experiences_type CHECK (type IN ('EXECUTIVE', 'BOARD')),
    CONSTRAINT ck_experiences_dates CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX idx_experiences_profile_id ON experiences (profile_id);
CREATE TRIGGER trg_experiences_set_updated_at BEFORE UPDATE ON experiences FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE sector_expertise (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    profile_id       UUID        NOT NULL,
    sector           VARCHAR     NOT NULL,
    years_experience INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_sector_expertise PRIMARY KEY (id),
    CONSTRAINT fk_sector_expertise_profile FOREIGN KEY (profile_id) REFERENCES executive_profiles (id) ON DELETE CASCADE,
    CONSTRAINT ck_sector_expertise_years CHECK (years_experience >= 0),
    CONSTRAINT uq_sector_expertise_profile_sector UNIQUE (profile_id, sector)
);
CREATE TRIGGER trg_sector_expertise_set_updated_at BEFORE UPDATE ON sector_expertise FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE qualifications (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    profile_id    UUID        NOT NULL,
    title         VARCHAR     NOT NULL,
    institution   VARCHAR     NOT NULL,
    year_obtained INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_qualifications PRIMARY KEY (id),
    CONSTRAINT fk_qualifications_profile FOREIGN KEY (profile_id) REFERENCES executive_profiles (id) ON DELETE CASCADE,
    CONSTRAINT ck_qualifications_year CHECK (year_obtained BETWEEN 1900 AND 2100)
);
CREATE INDEX idx_qualifications_profile_id ON qualifications (profile_id);
CREATE TRIGGER trg_qualifications_set_updated_at BEFORE UPDATE ON qualifications FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE referees (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    profile_id      UUID        NOT NULL,
    name            VARCHAR     NOT NULL,
    relationship    VARCHAR     NOT NULL,
    email_encrypted TEXT        NOT NULL,
    phone_encrypted TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_referees PRIMARY KEY (id),
    CONSTRAINT fk_referees_profile FOREIGN KEY (profile_id) REFERENCES executive_profiles (id) ON DELETE CASCADE
);
COMMENT ON COLUMN referees.email_encrypted IS 'The application encrypts this value with AES-GCM before storage; collect only what the vetting purpose needs.';
COMMENT ON COLUMN referees.phone_encrypted IS 'The application encrypts this value with AES-GCM before storage; collect only what the vetting purpose needs.';
CREATE INDEX idx_referees_profile_id ON referees (profile_id);
CREATE TRIGGER trg_referees_set_updated_at BEFORE UPDATE ON referees FOR EACH ROW EXECUTE FUNCTION set_updated_at();
