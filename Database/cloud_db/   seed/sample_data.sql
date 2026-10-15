-- sample_data.sql
-- DUMMY data for testing only. Never put real evidence or real passwords here.
-- Run AFTER schema and triggers.

INSERT INTO roles (role_name) VALUES
    ('admin'), ('journalist'), ('lawyer'), ('investigator');

INSERT INTO users (full_name, email, password_hash, role_id) VALUES
    ('Amina Test', 'amina@example.com', 'dummy_hash_1', (SELECT role_id FROM roles WHERE role_name = 'journalist')),
    ('Laura Test', 'laura@example.com', 'dummy_hash_2', (SELECT role_id FROM roles WHERE role_name = 'lawyer'));

INSERT INTO cases (case_number, title, description, region, incident_date, created_by) VALUES
    ('CASE-2026-001', 'Building collapse documentation', 'Sample case for testing', 'Region A', '2026-09-15',
     (SELECT user_id FROM users WHERE email = 'amina@example.com'));

INSERT INTO case_members (case_id, user_id, access_level) VALUES
    (1, (SELECT user_id FROM users WHERE email = 'amina@example.com'), 'owner'),
    (1, (SELECT user_id FROM users WHERE email = 'laura@example.com'), 'view');

INSERT INTO evidence (evidence_id, case_id, submitted_by, media_type, sha256_hash,
                      captured_at, latitude, longitude, device_id, attestation_verified)
VALUES ('11111111-1111-1111-1111-111111111111', 1,
        (SELECT user_id FROM users WHERE email = 'amina@example.com'),
        'video',
        'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',  -- dummy hash
        '2026-09-15 14:30:00+00', 33.312800, 44.361500, 'device-abc-123', TRUE);

INSERT INTO custody_events (evidence_id, event_type, actor_id, details) VALUES
    ('11111111-1111-1111-1111-111111111111', 'captured',       NULL, '{"source": "sandbox"}'),
    ('11111111-1111-1111-1111-111111111111', 'hashed',         NULL, '{"algo": "SHA-256"}'),
    ('11111111-1111-1111-1111-111111111111', 'receipt_issued', NULL, '{"by": "server"}'),
    ('11111111-1111-1111-1111-111111111111', 'uploaded',       NULL, '{"storage": "s3"}');
