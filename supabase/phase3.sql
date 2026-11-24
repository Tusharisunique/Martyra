-- Development project only: this removes Phase 2 test profiles and their evidence.
delete from profiles;

alter table profiles add column pubkey text not null;

create table anchor_batches (
  id uuid primary key default gen_random_uuid(),
  root text not null check (root ~ '^[0-9a-f]{64}$'),
  leaves text[] not null,
  chain_id integer not null,
  tx_hash text,
  block_number bigint,
  anchored_at timestamptz,
  status smallint not null default 2 check (status between 2 and 4),
  created_at timestamptz not null default now()
);

alter table evidence
  add column record text not null,
  add column sig text not null,
  add column batch_id uuid references anchor_batches (id);

create index evidence_unbatched on evidence (received_at) where batch_id is null;

create or replace function evidence_guard() returns trigger
language plpgsql as $$
begin
  if new.status < old.status then
    raise exception 'status moves forward only';
  end if;
  if (new.sha256, new.claimed_sha256, new.path, new.received_at, new.record, new.sig)
     is distinct from (old.sha256, old.claimed_sha256, old.path, old.received_at, old.record, old.sig) then
    raise exception 'evidence fields are immutable';
  end if;
  return new;
end $$;

alter table anchor_batches enable row level security;
create policy batches_read on anchor_batches for select to authenticated using (true);
