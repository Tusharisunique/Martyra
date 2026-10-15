-- 04_custody_events.sql
-- Chain of custody as an append-only log. Every state change is a NEW row.
-- event_hash / prev_event_hash form a hash chain: editing an old row
-- breaks every hash after it (filled automatically by a trigger).

CREATE TABLE custody_events (
    event_id         BIGSERIAL PRIMARY KEY,
    evidence_id      UUID NOT NULL REFERENCES evidence(evidence_id),
    event_type       VARCHAR(40) NOT NULL CHECK (event_type IN (
                        'captured',
                        'hashed',
                        'receipt_issued',
                        'queued_for_upload',
                        'uploaded',
                        'anchored',
                        'confirmed',
                        'file_missing_after_capture',
                        'accessed',
                        'assigned_to_case'
                     )),
    actor_id         INT REFERENCES users(user_id),   -- NULL for system/device events
    details          JSONB NOT NULL DEFAULT '{}',     -- e.g. {"tx_id": "...", "ip": "..."}
    prev_event_hash  CHAR(64),                        -- hash of the previous event for this evidence
    event_hash       CHAR(64) NOT NULL,               -- hash of this event + prev_event_hash
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_custody_evidence ON custody_events(evidence_id, event_id);
CREATE INDEX idx_custody_type ON custody_events(event_type);
