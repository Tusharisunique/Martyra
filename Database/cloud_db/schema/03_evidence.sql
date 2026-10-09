-- 03_evidence.sql
-- One row per captured file. The FILE itself lives in object storage (S3/IPFS);
-- the DB stores its fingerprint (SHA-256) and where to find it.
-- Core fields (hash, GPS, time, signature) are immutable (see triggers/append_only.sql).
-- Fields filled in later: case_id, storage_url, server_receipt, attestation_verified.

CREATE TABLE evidence (
    evidence_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id              INT REFERENCES cases(case_id),      -- NULL until assigned to a case
    submitted_by         INT NOT NULL REFERENCES users(user_id),
    media_type           VARCHAR(10) NOT NULL CHECK (media_type IN ('photo', 'video', 'audio')),
    sha256_hash          CHAR(64) NOT NULL,                   -- the "fingerprint"
    storage_url          TEXT,                                -- NULL until the full file is uploaded
    file_size_bytes      BIGINT,
    captured_at          TIMESTAMPTZ NOT NULL,
    latitude             NUMERIC(9,6) CHECK (latitude  BETWEEN -90  AND 90),
    longitude            NUMERIC(9,6) CHECK (longitude BETWEEN -180 AND 180),
    device_id            VARCHAR(100) NOT NULL,
    device_signature     TEXT,                                -- hash signed by the phone's hardware key
    attestation_verified BOOLEAN NOT NULL DEFAULT FALSE,      -- did the server verify the device/app is genuine?
    server_receipt       TEXT,                                -- server-signed timestamp receipt, issued immediately
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_evidence_hash ON evidence(sha256_hash);
CREATE INDEX idx_evidence_case ON evidence(case_id);
CREATE INDEX idx_evidence_captured_at ON evidence(captured_at);
CREATE INDEX idx_evidence_submitted_by ON evidence(submitted_by);
