-- Recreate evidence_sessions view with max_seq and missing-segment count.
-- Run once in Supabase Dashboard → SQL Editor.
create or replace view evidence_sessions with (security_invoker = true) as
select
  user_id,
  session,
  min(captured_at)                       as started_at,
  count(*)::integer                      as segments,
  sum(size_bytes)                        as bytes,
  bool_and(hash_ok)                      as hashes_ok,
  max(status)                            as status,
  max(seq)::integer                      as max_seq,
  (max(seq) + 1 - count(*))::integer     as missing
from evidence
group by user_id, session;
