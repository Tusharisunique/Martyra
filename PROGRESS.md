# Martyra – Progress

## Phase 1 (Completed)
- Project scaffolded: Gradle 8.11.1 / AGP 8.7.3 / Compose BOM 2024.12.01 / compileSdk 35 / minSdk 26
- Core single-module camera app with isolated capture process (`:capture`), Messenger IPC, multi-process preview, 3-second segments, cryptographic sealing, safe IPC upload trigger, isolated WorkManager, SQLCipher encrypted DB, 9:16 portrait capture and preview, winking animated logo, and red recording shutter.

## Phase 2: Server, Supabase & Android Cloud Connection (Completed)
- **Supabase Backend**:
  - `supabase/schema.sql`: Profiles, evidence table, `evidence_guard` trigger enforcing forward-only status and immutable hashes/paths, RLS policies, `evidence_sessions` view, and private `evidence` storage bucket.
  - `supabase/make_admin.sql`: Role assignment script for admin viewer privileges.
- **Node API Server**:
  - `server/server.js`: Minimal Fastify server (101 lines, budget ≤ 110 lines) with `@supabase/supabase-js` and `fastify` only.
  - `onRequest` authentication hook verifying Supabase Auth Bearer token before parsing request body (enforcing 401 before reading large payloads).
  - Validation via Fastify schema (session, seq, kind, ts, sha256, device, name) and strict coordinate range checks.
  - Independent server-side SHA-256 fingerprinting matching stored file bytes.
  - Profile upsert and disabled status enforcement (returns 403 when disabled).
  - Idempotent duplicate check returning 200 `{"duplicate":true}` without duplicating rows or storage objects.
  - Zero sensitive logging (logger disabled, startup port log only, error messages only).
- **Android Integration**:
  - `Net.kt` (new file, 105 lines): Silent anonymous Supabase authentication, Keystore-backed encrypted refresh token storage (`vaultKey`), in-memory access token caching with pre-expiry refresh, and streaming `HttpUploader` (chunked streaming, no full buffering).
  - `Upload.kt`: Removed dead `LocalUploader` and `cloud/` directory, switched to `HttpUploader` on `Dispatchers.IO`, and added 15-second linear WorkManager backoff.
  - `MainActivity.kt`: Removed dead local `user_id` generation and unused imports.
  - `build.gradle.kts`: Added `BuildConfig` fields (`SUPABASE_URL`, `SUPABASE_KEY`, `SERVER_URL`), debug fallback to `http://localhost:3000`, and enabled BuildConfig feature.
  - Network Security: Cleartext traffic restricted strictly to `localhost` and `127.0.0.1` in `src/debug/res/xml/network_security_config.xml` via debug manifest overlay. Release build remains strictly HTTPS-only with zero cleartext permissions.
- **Verification & Quality**:
  - `server/server.js`: **101 lines** (budget ≤ 110 lines)
  - Production server dependencies: **2** (`fastify`, `@supabase/supabase-js`)
  - Kotlin code line count: **1313 lines** across **7 files** (baseline 1232 lines, net +81 lines, budget ≤ +90 lines)
  - `assembleDebug`: **BUILD SUCCESSFUL**
  - `assembleRelease`: **BUILD SUCCESSFUL** (ProGuard/R8 passed, verified no cleartext config in merged release manifest)
  - `lintDebug`: **0 errors**
  - Untracked secrets: `.env`, `local.properties`, `node_modules` verified gitignored and uncommitted.

## Phase 2 Runtime Fixes & Stability
- **1-Click Reliable Video Stop**:
  - `CaptureService.kt`: `notifyState()` now checks `sessionId != 0L` rather than internal CameraX recording reference, providing immediate stopped-state confirmation upon stop request.
  - `startSegment()`: Added `if (sessionId == 0L) return` guard to prevent race conditions from launching zombie segments if a stop was issued during segment rollover.
  - `stopLoop()`: Unconditionally invokes `recording?.stop()` rather than guarding on stale `s != 0L`.
  - `MainActivity.kt`: Immediately updates local UI state on stop, stops the timer, and debounces shutter clicks (500 ms) to prevent accidental rapid re-triggers.
  - `Screens.kt`: Added tactile spring scale bounce animation on every shutter tap.
- **Recording Notification Lifecycle**:
  - `stopForeground(STOP_FOREGROUND_REMOVE)` is now called immediately inside `CaptureService.stopRecording()`, instantly removing the "Recording" notification from the status bar instead of keeping it for 10-20 seconds while segments finalize and hash.
- **PostgreSQL Generated Column Fix (500 Server Error)**:
  - Root cause resolved: in `server/server.js`, `hash_ok` was being passed in the `evidence.insert()` payload. Because `hash_ok` is defined as `GENERATED ALWAYS AS (sha256 = claimed_sha256) STORED` in Postgres, every insert was rejected with `cannot insert a non-DEFAULT value into column "hash_ok"`, returning HTTP 500 to the client and blocking all uploads.
  - Removed `hash_ok` from the insert payload; returned `{ hash_ok: hashOk }` in the API response.
- **Background Upload & Swipe Persistence**:
  - Dedicated `UploadService`: runs in the main process with `FOREGROUND_SERVICE_DATA_SYNC` and a partial `WakeLock` (up to 10 min), keeping CPU awake and draining outbox even if the phone screen turns off or app is backgrounded.
  - Resilient `drainOutbox()` in `Upload.kt` protected by atomic flag `isDrainingOutbox`, preventing race conditions or SQLite database locks between `UploadService` and `UploadWorker`.
  - WorkManager expedited compliance: added `getForegroundInfo()` to `UploadWorker` ensuring it can run as an expedited background worker without throwing runtime exceptions.
  - Fixed-length streaming mode with 64 KB buffers in `Net.kt`, dramatically increasing upload throughput for video segments.
  - Added `Db.ensurePending()` to recover and re-queue any files lingering in `outbox/`.
  - Added `onResume()` upload trigger in `MainActivity.kt`.
- **End-to-End Verification**:
  - Verified on live connected phone and Supabase: all pending video segments (`1792348057073` seq 0, seq 1, and `1792348524549` seq 0) successfully uploaded to Supabase storage and recorded in the `evidence` table with `hash_ok: true`. Outbox on device verified completely empty.

## Ready for End-to-End Acceptance Testing (Phase 2)
1. Run `supabase/schema.sql` on your Supabase project and enable Anonymous Sign-Ins in Auth settings.
2. Put the project URL and service role key in `server/.env` and start the server (`npm start`).
3. Put the project URL and publishable anon key in `local.properties`.
4. Connect phone via USB, allow USB debugging on the phone prompt, and run `adb reverse tcp:3000 tcp:3000`.
5. Run the debug build on the device (`adb install -r app/build/outputs/apk/debug/app-debug.apk`): tap Allow on the battery optimization prompt so photos and video segments upload seamlessly in the background.
