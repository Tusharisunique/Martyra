-- Run once on a fresh Supabase project (SQL Editor).

create function is_admin() returns boolean
language sql stable as $$
  select coalesce(auth.jwt() -> 'app_metadata' ->> 'role', '') = 'admin'
$$;

create table profiles (
  id uuid primary key references auth.users on delete cascade,
  name text not null check (char_length(name) between 1 and 40),
  disabled boolean not null default false,
  created_at timestamptz not null default now()
);

create table evidence (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references profiles (id) on delete cascade,
  session bigint not null,
  seq integer not null check (seq >= 0),
  kind text not null check (kind in ('video', 'photo')),
  path text not null,
  captured_at timestamptz not null,
  lat double precision,
  lon double precision,
  device text not null,
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  claimed_sha256 text not null check (claimed_sha256 ~ '^[0-9a-f]{64}$'),
  hash_ok boolean generated always as (sha256 = claimed_sha256) stored,
  size_bytes integer not null check (size_bytes > 0),
  received_at timestamptz not null default now(),
  status smallint not null default 2 check (status between 2 and 4),
  unique (user_id, session, seq)
);

create function evidence_guard() returns trigger
language plpgsql as $$
begin
  if new.status < old.status then
    raise exception 'status moves forward only';
  end if;
  if (new.sha256, new.claimed_sha256, new.path, new.received_at)
     is distinct from (old.sha256, old.claimed_sha256, old.path, old.received_at) then
    raise exception 'evidence fields are immutable';
  end if;
  return new;
end $$;

create trigger evidence_guard before update on evidence
for each row execute function evidence_guard();

alter table profiles enable row level security;
alter table evidence enable row level security;

create policy profiles_read on profiles for select to authenticated
  using (id = (select auth.uid()) or is_admin());

create policy evidence_read on evidence for select to authenticated
  using (user_id = (select auth.uid()) or is_admin());

create view evidence_sessions with (security_invoker = true) as
select user_id, session,
       min(kind) as kind,
       min(captured_at) as started_at,
       count(*) as segments,
       sum(size_bytes) as bytes,
       bool_and(hash_ok) as hashes_ok,
       min(status) as status
from evidence
group by user_id, session;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('evidence', 'evidence', false, 52428800, array['video/mp4', 'image/jpeg'])
on conflict (id) do nothing;

create policy evidence_files_admin_read on storage.objects for select to authenticated
  using (bucket_id = 'evidence' and is_admin());
