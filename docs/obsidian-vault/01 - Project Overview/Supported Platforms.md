---
tags: [overview]
---
# Supported Platforms

| Platform | Status | How |
|---|---|---|
| **Fire TV / Android TV** (Android 6.0+, API 23) | Supported | `androidApp` APK/AAB; ExoPlayer (default) or libmpv (Android 8+) |
| Windows | **Removed** | Use Jellyfin's own web app in a browser |
| Web browser | Never existed | Sharkfin has no web version; Jellyfin's web app covers browsers |

## What was removed with the Windows app
- The `desktopApp` module: `Main.kt`, the XInput `GamepadBridge`, jpackage MSI/EXE/portable packaging, icons.
- Windows-only shared code: the libmpv-via-JNA engine (`MpvEngine`, `LibMpv`, `MpvSelfTest`),
  `DesktopPlatform`, `DesktopStorage` (DPAPI vault, `%APPDATA%` files), `DesktopUpdater`,
  `DesktopUiSounds`; the JNA and coroutines-swing dependencies.
- Windows-only features in shared code: the F11 fullscreen hook (`Platform.toggleFullscreen`), the
  "Hardware decoding (D3D11)" and "Audio passthrough" rows in Settings → Playback (never shown on
  Android), and the local HDR tone-mapping capability (`canToneMapHdr`; only mpv on Windows had it).
- CI: the Windows job (libmpv fetch, packaging, self-test, launch screenshot) and the Linux desktop
  screenshot step; Windows files are no longer published to `latest-build`.

## What was kept on purpose
- **The shared module's JVM target** (renamed `desktop` → `jvm`): it isn't shipped, but it runs the
  unit tests, the Compose UI (keyboard/D-pad) tests and the real-server integration tests quickly
  without an emulator. It needs only a few platform pieces (`jvmMain`: OkHttp engine, image
  decoding, the D-pad flag).
- `arrowKeyNavigation()` (used by the JVM UI tests; a no-op where Android moves focus itself), mouse
  handling in the player (Android works with mice), the mpv Android engine.

## Related notes
[[App Overview]] · [[Tech Stack]] · [[Build and Run]] · [[Change Log]]
