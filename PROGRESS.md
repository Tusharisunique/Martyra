# Martyra – Progress

## Done
- Project scaffolded: Gradle 8.11.1 / AGP 8.7.3 / Compose BOM 2024.12.01 / compileSdk 35 / minSdk 26
- Stage 1: Core single-module camera app with 6 Kotlin source files, pure Compose foundation UI, black-and-white theme, vector logo, and SQLCipher encrypted database.
- Stage 2 (Fully Completed): Isolated capture process, Messenger IPC, multi-process preview, 3-second segments, cryptographic sealing, safe IPC upload trigger, isolated WorkManager, encrypted DB, local cloud storage, integrity check.
- Bug-fix round:
  - **Cross-process broadcast fix**: `CaptureService.notifySealedToMainProcess()` now uses `setClassName(packageName, UploadReceiver::class.java.name)` for reliable cross-process explicit broadcast delivery, instead of relying on class reference from the `:capture` process.
  - **Photo capture fix**: Removed the flawed rebind-for-photo-mode logic. `ImageCapture` is now always bound together with `Preview` and `VideoCapture` in `bindCamera()` (with `CAPTURE_MODE_MINIMIZE_LATENCY`), so `takePhoto()` just calls `img.takePicture(...)` directly — no unbind/rebind race.
  - **Profile screen redesign**: Full B&W redesign with winking Martyra face logo (`WinkFace` composable — single `Canvas`, 1.6 s keyframe loop with eye close/open and face fade-in), bottom-border-only text field, "Enter" button with letter-spacing and square corners.
  - **Logo wink animation**: `WinkFace` is a self-contained `@Composable` driven by `InfiniteTransition` keyframes matching the reference Wink design (fade, scale, eye wink with custom easing, smile). Shown on the Profile screen.
  - **Video recording fix**: Guarded `startRecording()` against uninitialized `VideoCapture`, made `startSegment()` parameterless with internal safety checks, and handled `event.hasError()` during finalize events so corrupt segments are discarded cleanly without crashing the loop.
  - **Red shutter button while recording**: Shutter ring and inner indicator change to solid red when video recording is active, returning to white when idle.
  - **Photo shutter animation**: Added a spring-driven scale bounce on tap in photo mode for clear capture feedback.
  - **Permission re-check ("Only this time" support)**: App now verifies camera and microphone permissions on every launch before binding `CaptureService`, re-prompting the user if "only this time" permission expired, without clearing the stored user profile.
- Verification & Quality:
  - `assembleDebug`: **BUILD SUCCESSFUL**
  - `assembleRelease`: **BUILD SUCCESSFUL** (ProGuard / R8 minification + resource shrinking passed)
  - `lintDebug`: **0 errors**

## Ready for Device Run & Acceptance Testing
1. Launch app in Android Studio on emulator or physical device.
2. Verify Profile screen: winking logo animates, name field with underline, "Enter" button (disabled until name typed).
3. Verify Screen B (Main): full-screen camera preview, mode toggle between Video and Photo.
4. Tap record: shutter turns red, ongoing notification, elapsed timer, 3-second segments continuously sealed into `outbox/`. Tap stop: shutter turns white.
5. Photo mode: tap shutter → spring bounce animation → sealed `.enc` file appears in `outbox/` immediately.
6. Background test: press Home — foreground service keeps recording in `:capture` process.
7. Re-launch Activity: binds to ongoing recording, displays active elapsed time, resumes preview.
8. Permissions: if "only this time" permission was granted, subsequent launch re-requests permissions seamlessly.

