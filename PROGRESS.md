# Martyra – Progress

## Done
- Project scaffolded: Gradle 8.11.1 / AGP 8.7.3 / Compose BOM 2024.12.01 / compileSdk 35 / minSdk 26
- Stage 1: Core single-module camera app with 6 Kotlin source files, pure Compose foundation UI, black-and-white theme, vector logo, and SQLCipher encrypted database.
- Stage 2 (Fully Completed):
  - **Isolated Capture Process**: `CaptureService` runs in `android:process=":capture"` (`FR-23`).
  - **Messenger IPC**: Full bidirectional communication (`MSG_REGISTER`, `MSG_UNREGISTER`, `MSG_SET_SURFACE`, `MSG_START_RECORDING`, `MSG_STOP_RECORDING`, `MSG_TAKE_PHOTO`, `MSG_STATE`) with recording state and timestamp recovery on Activity restart.
  - **Multi-Process Camera Preview**: Full-screen `SurfaceView` feeding hardware `Surface` over IPC to CameraX `SurfaceRequest`.
  - **Continuous 3-Second Segments**: Auto-rotating segment loop in foreground service with persistent recording notification (`FR-7`, `FR-8`).
  - **Cryptographic Sealing**: Zero-copy memory-mapped SHA-256 hashing, hardware Keystore AES-256-GCM streaming encryption, atomic `.tmp` sync and rename to `.enc` carrying timestamp, GPS, and hash (`FR-12`).
  - **Safe IPC Upload Trigger**: `CaptureService` never touches SQLite, SharedPreferences, or WorkManager (`FR-24`); sends explicit `ACTION_SEALED` broadcast to non-exported `UploadReceiver` in the main process.
  - **Isolated WorkManager**: WorkManager automatic initialization disabled in `:capture` process (`FR-25`); initialized on-demand exclusively in the main process.
  - **Encrypted Database & Forward-Only State**: SQLCipher encrypted `evidence.db`, forward-only status trigger (`CAPTURED` 0 -> `SYNCING` 1 -> `UPLOADED` 2). Retry logic respects trigger aborts without down-grading statuses (`FR-14` to `FR-18`).
  - **Local Cloud Storage**: `LocalUploader` writes decrypted files to `cloud/` retaining original SHA-256 for round-trip verification (`FR-21`).
  - **Integrity Check**: Device root check (`isRooted`) disables recording on compromised devices (`FR-11`).
  - **Zero Leak Handlers**: All IPC Handlers utilize `WeakReference` to prevent memory leaks.
- Verification & Quality:
  - `assembleDebug`: **BUILD SUCCESSFUL**
  - `assembleRelease`: **BUILD SUCCESSFUL** (ProGuard / R8 minification + resource shrinking passed)
  - `lintDebug`: **0 errors**

## Ready for Device Run & Acceptance Testing
1. Launch app in Android Studio on emulator or physical device.
2. Verify Screen A (Profile) appears on first launch, prompts for name, requests permissions, and persists user ID.
3. Verify Screen B (Main): full-screen camera preview, mode toggle between Video and Photo.
4. Tap record: ongoing notification, elapsed timer, 3-second segments continuously sealed into `outbox/`.
5. Background test: press Home or swipe away Activity — foreground service keeps recording in `:capture` process.
6. Re-launch Activity: binds to ongoing recording, displays active elapsed time, and resumes preview.
