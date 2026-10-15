# cloud_db

PostgreSQL schema for the cloud backend of the tamper-proof evidence app.

## What this database does
- Stores users, cases and evidence **metadata** (files live in object storage)
- Keeps an append-only, hash-chained chain-of-custody log
- Tracks Merkle batches anchored on the blockchain

## Folder layout
| Folder | Purpose |
|---|---|
| `schema/` | Tables. Run in numbered order. |
| `triggers/` | Append-only protection and hash chaining. |
| `seed/` | Dummy data for testing. |

## How to run
```bash
psql -d martyra -f schema/01_users_roles.sql
psql -d martyra -f schema/02_cases.sql
psql -d martyra -f schema/03_evidence.sql
psql -d martyra -f schema/04_custody_events.sql
psql -d martyra -f schema/05_merkle_anchors.sql
psql -d martyra -f triggers/append_only.sql
psql -d martyra -f seed/sample_data.sql
```

## Design notes
- The DB is tamper-**evident**, not tamper-**proof**. True immutability comes from
  anchoring Merkle roots (and the custody chain head) on the blockchain.
- Only hashes go on-chain, never files or GPS/metadata.
- Core evidence fields are locked by triggers; a DB superuser could still drop the
  triggers, which is why the custody chain head is anchored on-chain.
