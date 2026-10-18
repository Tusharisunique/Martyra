# Martyra

Decentralized, tamper-evident camera and evidence vault.

## Phase 2: Server and Supabase Architecture

```
Android App  ──HTTPS──>  API Server (Node/Fastify)  ──(Service Key)──>  Supabase
     │                                                                     │
     └───(Sign-in only)──> Supabase Auth (Anonymous)                       │
                                                                   (Auth, DB, Storage)
```

- **Android App:** Captures 9:16 video/photo, seals locally with AES-256-GCM + SQLCipher, anonymously authenticates with Supabase Auth, streams encrypted outbox to server via `HttpUploader`.
- **API Server:** Plain ES-module Fastify server (`server/server.js`). Verifies token before reading request body, independently computes SHA-256 fingerprint, stores files in private Supabase Storage (`evidence` bucket), and inserts records into Postgres `evidence` table.
- **Supabase:** Postgres database with row-level security (RLS), append-only trigger guard preventing hash mutation or status regression, and private Storage bucket.

---

## Setup & Running

### 1. Supabase Project Setup
1. Create a project at [supabase.com](https://supabase.com).
2. Under **Authentication > Providers > Email**, enable **Anonymous sign-ins**.
3. In **SQL Editor**, run `supabase/schema.sql`.
4. Copy the Project URL, Publishable (anon) key, and Service Role (secret) key.

### 2. API Server Configuration
1. In `server/`, create `.env` based on `.env.example`:
   ```bash
   SUPABASE_URL=https://<your-project>.supabase.co
   SUPABASE_SECRET_KEY=<your-service-role-key>
   PORT=3000
   HOST=127.0.0.1
   ```
2. Install dependencies and start:
   ```bash
   cd server
   npm install
   npm start
   ```

### 3. Android Configuration
1. Add the following to your root `local.properties`:
   ```properties
   supabase.url=https://<your-project>.supabase.co
   supabase.key=<your-publishable-anon-key>
   server.url.debug=http://localhost:3000
   server.url.release=https://your-production-server.com
   ```
2. For local testing with phone connected over USB:
   ```bash
   adb reverse tcp:3000 tcp:3000
   ```
3. Run the debug build from Android Studio or with `./gradlew assembleDebug`.

---

## Guarantees

- **Server-Verified Integrity:** The stored fingerprint is computed directly by the server from the received bytes; clients cannot spoof or tamper with fingerprints.
- **Access Control:** Clients cannot write to Storage or the database directly; existing evidence rows and stored files are immutable.
- **Row-Level Security:** Users can only query their own evidence; only users with the `admin` role can query all records and generate signed links for playback.
- **At-Least-Once Delivery:** Retrying an upload is idempotent and safe (`duplicate: true` on re-send).

---

## Limitations

- **Server Availability:** The server must be reachable. While unreachable, the app keeps all evidence encrypted on the device and retries via WorkManager backoff.
- **Anonymous Identity:** Clearing the app's local storage (`pm clear`) or losing the stored refresh token initiates a new anonymous user session.
- **Quotas & Rate Limits:** No rate limiting or CAPTCHA is implemented in Phase 2; anyone could register anonymous accounts until CAPTCHA/quotas are enforced.
- **Transport vs at-rest Encryption:** The cloud copy is stored as standard media files over TLS; device-side encryption protects local phone storage only.
- **Supabase Free Tier Limits:** Supabase free plan enforces project limits (storage space, 50MB file limit, bandwidth, and inactivity pauses).
- **Time Trust:** Server and database timestamps represent the trusted receive time; independent anchoring on-chain is introduced in Phase 3.

---

## Phase 3: Admin Viewer (`viewer/`)

A read-only static website that lives entirely in `viewer/`. No build step, no server, no npm, no frameworks. Three files — `index.html`, `style.css`, `app.js` — opened directly in any modern browser (or served with any static server, e.g. `npx serve viewer/`).

### One-time admin setup

1. In the **Supabase Dashboard → Authentication**, create an account with email and password for the admin.
2. In **SQL Editor**, run `supabase/make_admin.sql` replacing `you@example.com` with that email.
3. The admin must **sign out and sign back in** to receive a token with the updated role.

### Running the viewer

```bash
# Quick local static server on port 8080 (port 3000 is used by the API server):
npx serve -l 8080 viewer/
# Then open http://localhost:8080 in a browser.
```

Or simply open `viewer/index.html` in a browser that allows `file://` CSP — Chrome may need `--allow-file-access-from-files`.

### What you can do

| Action | Route |
|---|---|
| List all users | `#/` |
| See a user's sessions | `#/u/<userId>` |
| Play a session's evidence | `#/s/<userId>/<session>` |

For each video segment the viewer fetches a 300-second signed URL, plays it in the browser, and runs a SHA-256 fingerprint check (`crypto.subtle`) against `row.sha256` stored in Supabase. A green ✓ means the bytes match the server-verified hash; a red ✗ means they differ.

### Security model

- Uses only the **publishable (anon) key** — never the service-role key.
- All reads go through Supabase **row-level security** (admin role required to see other users' data).
- No `innerHTML`, `outerHTML`, `eval`, or `document.write` anywhere.
- Strict CSP: only `self`, the jsDelivr CDN (SRI-pinned), and the Supabase project host are allowed.
