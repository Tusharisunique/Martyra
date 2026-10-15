-- append_only.sql
-- 1) custody_events: fully append-only (no UPDATE / DELETE / TRUNCATE).
-- 2) evidence: no DELETE / TRUNCATE; core fields cannot be changed.
--    Only case_id, storage_url, server_receipt, attestation_verified,
--    file_size_bytes may be filled in later.
-- 3) Automatically build the hash chain on custody_events.
-- Run AFTER all schema files.

-- ---------- 1. Generic rejection ----------
CREATE OR REPLACE FUNCTION reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: table is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER custody_no_update_delete
    BEFORE UPDATE OR DELETE ON custody_events
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER custody_no_truncate
    BEFORE TRUNCATE ON custody_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TRIGGER evidence_no_delete
    BEFORE DELETE ON evidence
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER evidence_no_truncate
    BEFORE TRUNCATE ON evidence
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- ---------- 2. Evidence: lock the core fields ----------
CREATE OR REPLACE FUNCTION guard_evidence_update() RETURNS trigger AS $$
BEGIN
    IF NEW.evidence_id      IS DISTINCT FROM OLD.evidence_id
    OR NEW.submitted_by     IS DISTINCT FROM OLD.submitted_by
    OR NEW.media_type       IS DISTINCT FROM OLD.media_type
    OR NEW.sha256_hash      IS DISTINCT FROM OLD.sha256_hash
    OR NEW.captured_at      IS DISTINCT FROM OLD.captured_at
    OR NEW.latitude         IS DISTINCT FROM OLD.latitude
    OR NEW.longitude        IS DISTINCT FROM OLD.longitude
    OR NEW.device_id        IS DISTINCT FROM OLD.device_id
    OR NEW.device_signature IS DISTINCT FROM OLD.device_signature
    OR NEW.created_at       IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION 'UPDATE on evidence is not allowed: core fields are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evidence_guard_update
    BEFORE UPDATE ON evidence
    FOR EACH ROW EXECUTE FUNCTION guard_evidence_update();

-- ---------- 3. Hash chaining ----------
CREATE OR REPLACE FUNCTION build_custody_hash() RETURNS trigger AS $$
DECLARE
    last_hash CHAR(64);
BEGIN
    SELECT event_hash INTO last_hash
    FROM custody_events
    WHERE evidence_id = NEW.evidence_id
    ORDER BY event_id DESC
    LIMIT 1;

    NEW.prev_event_hash := last_hash;
    NEW.event_hash := encode(
        digest(
            COALESCE(last_hash, '') || '|' ||
            NEW.evidence_id::text   || '|' ||
            NEW.event_type          || '|' ||
            COALESCE(NEW.actor_id::text, '') || '|' ||
            NEW.details::text       || '|' ||
            NEW.created_at::text,
            'sha256'),
        'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER custody_hash_chain
    BEFORE INSERT ON custody_events
    FOR EACH ROW EXECUTE FUNCTION build_custody_hash();
