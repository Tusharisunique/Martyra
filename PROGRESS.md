# Martyra – Progress

## Done
- Project scaffolded: Gradle 8.11.1 / AGP 8.7.3 / Compose BOM 2024.12.01 / compileSdk 35
- All 6 Kotlin source files written and compiling (502 lines total, all ≤ 140 lines per file)
- `Vault.kt`: AES-256-GCM Keystore key, 32-byte passphrase encrypted at rest, `seal()` (memory-mapped SHA-256 → encrypt → sync → rename → delete raw), `open()` decrypt
- `Db.kt`: SQLCipher 4.6.1 (`net.zetetic.database.sqlcipher`), schema + forward-only trigger + status index
- `Upload.kt`: `Uploader` fun interface, `LocalUploader`, `UploadWorker` (ingest → SYNCING → UPLOADED), `enqueueUpload`
- `CaptureService.kt`: bound `LifecycleService`, CameraX 1.4.1 (Preview + VideoCapture + ImageCapture), 3-second segment loop, foreground promotion (camera|microphone|location), `isRooted()`, leftover seal on create
- `Screens.kt`: Profile screen (logo via canvas, underlined field, Continue button) + Main screen (preview, shutter shapes, elapsed timer) — foundation-only, no Material
- `MainActivity.kt`: edge-to-edge, portrait, showProfile state, permission request, service binding via `surfaceProvider`
- AndroidManifest with correct permissions (no storage), black theme, logo/notification vectors, adaptive launcher icon
- `assembleDebug`: **BUILD SUCCESSFUL**

## Next
- Lint clean (in progress)
- Commit + push Stage 1
- Stage 2: move service to `:capture` process with Messenger IPC (FR-23 to FR-25)
- Acceptance tests on device

## Known Problems / Deviations
- `CaptureService.takePhoto()` temporarily unbinds VideoCapture to bind ImageCapture; a brief preview gap may occur — acceptable per PRD
- Segment boundary gap (~0.5–1 s) is expected and documented in PRD section 9
- Stage 2 (isolated process) not yet built
