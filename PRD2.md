# Martyra Phase 2: Server and Supabase (PRD2, v1, minimal)

**Builds on:** `PRD.md` and `final_flow.md` (the finished Android app). Those stay valid except where this file says otherwise.
**Adds:** a Node API server, a Supabase backend (Auth, Postgres, Storage), and the Android connection to them.
**Not in this phase:** the admin web viewer (only its contract is prepared, section 8), blockchain anchoring, the verifier tool.
**Companion files:** `final_flow2.md` (the flow), `BUILD_PROMPT2.md` (instructions for the AI builder)

---

## 1. Purpose

Replace the app's stand-in "cloud" folder with a real, central backend, so that:
1. Footage and metadata from every user land in one place (central storage and user management).
2. The **server**, not the phone, computes the fingerprint (SHA-256) of what it receives. An app cannot lie about it.
3. Everything is laid out so an admin web viewer can be added later with no backend changes.

## 2. Scope

**In scope**
- Supabase schema, security rules, private storage bucket, admin role (one SQL file)
- API server: one upload endpoint and a health check
- Android: silent anonymous sign-in, `HttpUploader`, build-time configuration, debug-only local HTTP
- Local development with the phone on a USB cable (`adb reverse`)

**Out of scope (do not build)**
Web viewer, admin endpoints, rate limiting or quotas, user-facing login, profile editing, push or realtime updates, Docker, Supabase CLI or local Supabase, hosting setup, tests, TypeScript, an ORM, any extra npm package beyond section 6.

## 3. Architecture and trust

```
Android app --HTTPS--> API server --(secret key)--> Supabase (Auth, Storage, Postgres)
      \--sign in only--> Supabase Auth                    ^
                                                          | read under security rules
                                            (later) Web viewer, admin login
```

- The app talks to Supabase **only** to sign in (public key). It never writes to Storage or the database directly.
- The server holds the only powerful credential (Supabase secret key), in its environment.
- The server decides identity from the verified token. The app never sends a user ID.
- The viewer (later) reads Supabase directly under row-level security, with an admin login.

## 4. One-time setup (done by the human, in the Supabase dashboard)

1. Create a Supabase project.
2. Authentication, Sign In / Providers: enable **Anonymous sign-ins**.
3. SQL Editor: run `supabase/schema.sql` (section 12).
4. Copy three values: the project URL, the **publishable** (anon) key, and the **secret** (service role) key. The secret key goes only into `server/.env`. The URL and publishable key go into the Android `local.properties`. Never paste any of them into chat, commits or source files.
5. Later, for the viewer: create an email and password user in the dashboard, then run `supabase/make_admin.sql` with that email.

## 5. Supabase requirements

- **SB-1 `profiles`:** one row per user (id from `auth.users`, name 1 to 40 characters, `disabled` flag). Created by the server on the first upload.
- **SB-2 `evidence`:** one row per segment or photo (columns in section 12). `unique (user_id, session, seq)`. The unique index also serves the viewer's queries, so no extra index.
- **SB-3 Status numbers** match the app's database: **2 received**, 3 anchored, 4 confirmed. New rows start at 2.
- **SB-4 Integrity trigger:** status can only increase; `sha256`, `claimed_sha256`, `path` and `received_at` can never change.
- **SB-5 Row-level security:** enabled on both tables. Clients get **select only**: own rows, or all rows for an admin. There are no insert, update or delete policies, so only the server (secret key) writes.
- **SB-6 Admin:** `is_admin()` is true when the token's `app_metadata.role` is `admin`. That field can only be set from the dashboard or SQL, never by a user.
- **SB-7 Storage:** private bucket `evidence`, 50 MB limit, MIME types `video/mp4` and `image/jpeg`. No client write policy. Admins can read objects. File path: `{user_id}/{session}/{seq as 4 digits}.{mp4|jpg}`.
- **SB-8 View `evidence_sessions`** (security invoker): per user and session, the segment count, bytes, start time, status and whether all hashes matched. Used by the viewer to list sessions.

## 6. Server requirements

**Stack:** Node 20.6 or newer, plain JavaScript (ES modules), no build step. Dependencies: `fastify` and `@supabase/supabase-js` only (exact versions pinned, checked with `npm view` at install time). Environment loaded with Node's `--env-file` (no dotenv).
**Files:** `server/server.js` (at most 110 lines), `server/package.json`, `server/.env.example`.
**Environment:** `SUPABASE_URL`, `SUPABASE_SECRET_KEY`, `PORT` (default 3000), `HOST` (default `127.0.0.1`; set `0.0.0.0` when hosted).

**Endpoints**
- **SRV-1** `GET /health` returns 200 `{"ok":true}`. No auth.
- **SRV-2** `PUT /v1/evidence/:session/:seq`, body = raw file bytes (`application/octet-stream`, limit 50 MB).
  Query parameters: `kind` (`video|photo`), `ts` (epoch ms), `lat`, `lon` (decimal degrees or `na`), `sha256` (the app's claimed hash, 64 hex), `device` (up to 64 characters), `name` (the user's name, 1 to 40 characters).

**Behavior of SRV-2, in this order**
1. **Auth first** (before the body is read): `Authorization: Bearer <token>` verified with `supabase.auth.getUser(token)`. Missing or invalid: **401**.
2. **Validate** `session` and `seq` (digits only), `kind`, `ts`, `lat`/`lon` (valid range or `na`), `sha256` (64 lowercase hex), `device`, `name`. Invalid: **400**. Empty body: **400**. Too large: **413**.
3. **Profile:** look up the user's profile. If missing, create it with `name`. If `disabled`: **403**.
4. **Duplicate check:** if a row for `(user, session, seq)` exists: same `claimed_sha256` returns **200** `{"duplicate":true}` and changes nothing; a different one returns **409**. Existing evidence is never overwritten.
5. **Hash** the received bytes with SHA-256 (this is the real fingerprint).
6. **Store** the file in Storage at the path from SB-7 (`upsert: true`, to complete an earlier half-finished attempt).
7. **Insert** the evidence row (server hash, claimed hash, size, device, location, captured time from `ts`). A unique-violation here counts as a duplicate (200).
8. Respond **200** `{"hash_ok": true|false}`. **If the hashes differ, the evidence is still stored and flagged** (`hash_ok` false). It is never dropped.
- **SRV-3** Any unexpected failure returns **500** `{"error":"server_error"}` with no internal details, so the app retries.
- **SRV-4** The server **never logs** URLs, query strings, tokens, names, locations or bodies (Fastify logger off; only startup and `console.error` of error messages).
- **SRV-5** No CORS. The viewer does not call this server.
- **SRV-6** The secret key never leaves the server (not in responses, logs or the repository).

## 7. Android requirements (changes only)

- **APP-1 Anonymous sign-in, lazily:** the first time an upload is needed, call Supabase Auth to sign in anonymously (`POST {SUPABASE_URL}/auth/v1/signup`, empty JSON body, `apikey` header with the publishable key; verify the exact request against the Supabase Auth docs). Keep the access token in memory with its expiry. Store the **refresh token** encrypted with the existing Keystore key. Refresh shortly before expiry with the refresh grant; the refresh token rotates, so always save the new one. If refresh fails permanently, sign in again.
- **APP-2 `HttpUploader`** (replaces `LocalUploader`): `PUT {SERVER_URL}/v1/evidence/{session}/{seq}` with the query parameters from SRV-2 and `Authorization: Bearer`. Stream the decrypted data (no full buffering), `HttpURLConnection` with connect timeout 10 s and read timeout 30 s. Return true only for HTTP 200. On 401 discard the cached access token. Any other status or `IOException` returns false, so the worker keeps the file and retries.
- **APP-3 Uploader interface** becomes `fun interface Uploader { suspend fun upload(data: InputStream, meta: Evidence): Boolean }`. The `key` parameter is removed: the server builds the path.
- **APP-4 Remove dead code:** `LocalUploader` and the `cloud/` folder; the locally generated user ID (the server derives identity now) and everything that used it. The profile name stays.
- **APP-5 Backoff:** upload job backoff is linear, 15 seconds (so a restarted server is picked up within minutes, not hours).
- **APP-6 Configuration (`BuildConfig`):** `SERVER_URL`, `SUPABASE_URL`, `SUPABASE_KEY`, read from `local.properties` (gitignored). Debug `SERVER_URL` defaults to `http://localhost:3000`; release `SERVER_URL` comes from a property and must be `https`. Missing values must not break the build (uploads simply fail and retry).
- **APP-7 Debug-only plain HTTP:** a `debug` source-set network security config allowing cleartext only for `localhost` and `127.0.0.1`. The release build stays HTTPS-only. Do not weaken the main manifest.
- **APP-8 Budget:** net addition of at most **90 lines of Kotlin**, one new file `Net.kt` (sign-in, token handling, `HttpUploader`), no new dependencies (`HttpURLConnection` and the built-in `org.json`). The app's file cap becomes 7 files.
- Status handling is unchanged: a 200 from the server means the app sets UPLOADED and deletes the local file. Statuses ANCHORED and CONFIRMED stay reserved for Phase 3.

## 8. Viewer-ready contract (built now so the viewer needs no backend work)

The future web viewer (static page, `supabase-js`, publishable key, admin email login) will use only these:

| Viewer action | Query |
|---|---|
| List users | `from('profiles').select('*')` (admin sees all) |
| List a user's sessions | `from('evidence_sessions').select('*').eq('user_id', id)` |
| List a session's segments in order | `from('evidence').select('*').eq('user_id', id).eq('session', s).order('seq')` |
| Play a segment | `storage.from('evidence').createSignedUrl(row.path, 300)`, played in order |
| Check a fingerprint | download the file, SHA-256 in the browser, compare with `row.sha256` |
| Show integrity and status | `hash_ok`, `status`, `captured_at`, `received_at`, `lat`, `lon`, `device` |

Nothing in the server, schema or app needs to change to support this.

## 9. Local development (phone on USB, server on the PC)

1. Terminal 1: `cd server && npm start`
2. Terminal 2: `adb reverse tcp:3000 tcp:3000` (repeat after reconnecting the cable)
3. Run the debug build from Android Studio. The phone's `localhost:3000` now reaches the server.

The phone and the PC both need internet (Supabase). The server listens only on `127.0.0.1`.

## 10. Guarantees and limitations

**Guarantees**
- The stored fingerprint is computed by the server from the stored bytes.
- Clients cannot write to Storage or the database, and existing evidence is never overwritten.
- A user can read only their own rows; only an admin can read everyone's.
- A disabled user's uploads are refused at the next upload, and the app keeps the files.
- Retrying an upload is always safe.

**Limitations (state them in the README)**
- The server must be reachable. While it is not, the app keeps everything encrypted and retries.
- Anonymous identity: clearing the app's data or losing the refresh token creates a new user. The admin may see two users with one name.
- No rate limits or quotas yet; anyone could create anonymous accounts. Add CAPTCHA for anonymous sign-ins and quotas before real use.
- The cloud copy is plaintext over TLS (the app-side encryption protects the phone only).
- Supabase's free plan has small limits (storage, per-file size, bandwidth, pausing after inactivity). Check the current terms.
- The server clock and the database clock are the trusted receive time. The blockchain anchor (Phase 3) will be the independent one.

## 11. Acceptance criteria

1. `supabase/schema.sql` runs on a fresh project without errors; `make_admin.sql` works for an existing user.
2. `GET /health` returns 200. A request with no token or a bad token returns 401.
3. A valid upload (made with `curl` and a token from anonymous sign-in) returns 200; the file exists in Storage at the expected path; the row exists; `sha256` equals `shasum -a 256` of the file; `hash_ok` is true.
4. The same request again returns 200 `{"duplicate":true}` and changes nothing.
5. A wrong claimed hash returns 200, the row is stored with `hash_ok = false`.
6. Setting `disabled = true` makes uploads return 403; setting it back makes them succeed.
7. Bad `kind`, `seq` or `sha256` returns 400; a body over 50 MB returns 413.
8. Security rules: a user token sees only its own rows and gets an error on insert, update and delete; the publishable key with no login sees nothing; an admin token sees all rows and the view and can create a signed URL. Verify with a throwaway script that is not committed.
9. The trigger rejects lowering `status` and changing `sha256`.
10. On the phone with `adb reverse`: record 10 s, then rows with `hash_ok = true` and files appear in Supabase, and the app's local files are deleted and rows are UPLOADED.
11. Stop the server, record, and the outbox grows; restart the server and it drains by itself (within a few minutes).
12. Airplane mode still queues encrypted files and uploads after reconnecting.
13. Static checks: `server.js` at most 110 lines; two production dependencies; `.env` is not tracked by git; the Android app contains no secret key (only the publishable key); `LocalUploader` and the local user ID are gone; Android net additions at most 90 lines in at most 7 files.

## 12. Decisions made for you (change any before building)

| ID | Decision |
|---|---|
| D1 | The file bytes travel in the request body; metadata in the query string; identity from the token only. |
| D2 | Anonymous sign-in, done lazily at the first upload (works even if the first launch was offline). |
| D3 | A hash mismatch is stored and flagged, never rejected. |
| D4 | Status numbers are shared with the app (2, 3, 4). |
| D5 | Plain JavaScript, Fastify and `supabase-js` only. |
| D6 | The user's name travels with each upload and the server creates the profile; there is no profile endpoint. |
| D7 | The viewer reads Supabase directly under row-level security; there are no admin endpoints. |
| D8 | Linear 15 s backoff for uploads. |
| D9 | No rate limits or quotas now (see limitations). |
| D10 | Server listens on `127.0.0.1` by default. |

## 13. Future

The web viewer, the `anchors` table and the anchoring worker (statuses 3 and 4), the verifier, quotas and CAPTCHA, email linking for users, hosting the server, signed upload URLs for large files, end-to-end encryption.

---

## Appendix: `supabase/schema.sql` (source of truth)

```sql
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
```

## Appendix: `supabase/make_admin.sql`

```sql
update auth.users
set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb) || '{"role":"admin"}'
where email = 'you@example.com';
```
The admin must sign in again afterwards to get a token with the new role.