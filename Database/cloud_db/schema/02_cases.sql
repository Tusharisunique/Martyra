-- 02_cases.sql
-- A case groups evidence from one incident.
-- case_members controls WHO may see a case (data-level access control).

CREATE TABLE cases (
    case_id        SERIAL PRIMARY KEY,
    case_number    VARCHAR(50) UNIQUE NOT NULL,
    title          VARCHAR(200) NOT NULL,
    description    TEXT,
    region         VARCHAR(100),
    incident_date  DATE,
    created_by     INT NOT NULL REFERENCES users(user_id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE case_members (
    case_id        INT NOT NULL REFERENCES cases(case_id),
    user_id        INT NOT NULL REFERENCES users(user_id),
    access_level   VARCHAR(10) NOT NULL CHECK (access_level IN ('view', 'edit', 'owner')),
    PRIMARY KEY (case_id, user_id)
);

-- Indexes for search ("all cases in this region between these dates")
CREATE INDEX idx_cases_region ON cases(region);
CREATE INDEX idx_cases_incident_date ON cases(incident_date);
