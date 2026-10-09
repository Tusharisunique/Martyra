-- 05_merkle_anchors.sql
-- Hashes are batched into a Merkle tree; only the ROOT goes on the blockchain.
-- Each evidence row stores a Merkle proof so it can be verified independently.

CREATE TABLE merkle_batches (
    batch_id               SERIAL PRIMARY KEY,
    merkle_root            CHAR(64) NOT NULL,
    leaf_count             INT NOT NULL CHECK (leaf_count > 0),
    custody_chain_head     CHAR(64),        -- latest custody hash, anchored too, so a DB admin
                                            -- cannot silently recompute the custody hash chain
    chain_name             VARCHAR(30) NOT NULL,         -- e.g. 'polygon'
    tx_id                  VARCHAR(100),                 -- blockchain transaction ID
    block_number           BIGINT,
    status                 VARCHAR(15) NOT NULL DEFAULT 'pending'
                           CHECK (status IN ('pending', 'submitted', 'confirmed', 'failed')),
    retry_count            INT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    anchored_at            TIMESTAMPTZ
);

CREATE TABLE evidence_merkle_proof (
    evidence_id  UUID PRIMARY KEY REFERENCES evidence(evidence_id),
    batch_id     INT NOT NULL REFERENCES merkle_batches(batch_id),
    leaf_index   INT NOT NULL,
    proof        JSONB NOT NULL      -- list of sibling hashes up to the root
);

CREATE UNIQUE INDEX idx_batches_tx ON merkle_batches(tx_id) WHERE tx_id IS NOT NULL;
CREATE INDEX idx_proof_batch ON evidence_merkle_proof(batch_id);
