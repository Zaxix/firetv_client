# Aurora TV

A Jellyfin client for **Amazon Fire TV / Android TV** and **Windows 10/11**, with an Apple TV-inspired
interface, native hardware-accelerated playback, and styled ASS/SSA subtitles rendered by libass on
both platforms.

* **Shared:** Kotlin Multiplatform + Compose Multiplatform (UI, Jellyfin client, playback logic)
* **Fire TV / Android TV:** Media3 ExoPlayer (hardware MediaCodec) + Jellyfin FFmpeg audio extension + libass (ass-media)
* **Windows:** libmpv (gpu-next / D3D11, hardware decoding) with libass, packaged with jpackage

| Docs | |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | Modules, playback flow, focus system, security |
| [Capability matrix](docs/CAPABILITIES.md) | Codecs, HDR, audio, subtitles per platform |
| [Test results](docs/TEST_RESULTS.md) | Unit, UI, real-server integration and Windows self-test results |
| [Known limitations](docs/KNOWN_LIMITATIONS.md) | What isn't supported or still needs hardware testing |
| [Screenshots](docs/screenshots/) | Captured from the real build against a real Jellyfin server |

## Features

* Add servers by IP, hostname or URL (`192.168.1.20`, `jellyfin.local`, `https://media.example.com/jellyfin`); probes the usual ports and schemes
* Multiple servers and users; switching servers and users; automatic reconnect at launch
* Tokens stored in the Android Keystore (AES-256-GCM) or with Windows DPAPI; tokens are redacted from logs
* Home: featured carousel, Continue Watching, Next Up, Recently Added per library, Favorites; rails load lazily as they scroll into view
* Movies, TV Shows and folder libraries with sort (A–Z, added, release, rating, last watched), filters (unwatched, watched, favorites) and paging
* Detail pages: backdrop, logo, metadata, resume/restart, favorite, watched, seasons and episodes, cast, More Like This, media info
* Search, Continue Watching, Favorites, Libraries (including collections)
* Player (tvOS-style): a scrubber with a time bubble, chapter ticks, and **intro/recap/credits ranges marked in yellow** (from server media segments, or chapter names like "Opening"/"Ending"). An **in-player episode browser** (season picker plus episode cards). Chapters, audio and subtitle sheets, and a playback info panel (play method, transcode reasons, decoder). Skip Intro/Recap/Credits, Up Next with countdown, resume and progress sync. A visible **Back** button; Back/Esc leaves the player in one press, and on Windows clicking the picture pauses and closing the window exits.
* tvOS-style navigation: top tab bar (Back from content goes to the tab bar), full-screen featured titles you can page with Left/Right, focus lift with a light sheen, rounded buttons, and the Inter typeface (open-source, in the style of San Francisco)
* Dark, Light and System themes across the entire app, including the player; artwork-derived accent colours
* Fire TV remote, keyboard, mouse and Xbox-compatible controllers (Windows)

The design follows tvOS's layout and behaviour but uses only original artwork and the open-source Inter font. Apple's SF Pro font may only be used on Apple platforms.

## Install

Download from the latest successful **Build** workflow run (Actions tab → run → *Artifacts*):

| Artifact | Contents |
|---|---|
| `android` | `AuroraTV-release.apk` (sideload this), `AuroraTV-debug.apk`, `AuroraTV-release.aab` |
| `windows` | `Aurora TV-x.y.z.msi`, `Aurora TV-x.y.z.exe` (installers), `AuroraTV-windows-x64-portable.zip` |
| `e2e` | Integration test report and UI screenshots |

### Fire TV (sideload)

1. On the Fire TV: **Settings → My Fire TV → Developer Options → Install unknown apps → Downloader → On**
   (on newer Fire OS, first enable Developer Options by pressing Select 7× on *Settings → My Fire TV → About → [device name]*).
2. Either install with **Downloader** from a URL where you've put the APK, or over ADB from a computer on the same network:
   ```
   adb connect <fire-tv-ip>:5555
   adb install -r AuroraTV-release.apk
   ```
3. Launch **Aurora TV** from *Your Apps & Games*.

If an install fails with a signature error, uninstall the previous build first (see *Release signing* below).

### Windows

Run the `.msi` or `.exe` installer (per-user, no admin needed), or unzip the portable build and run
`Aurora TV.exe`. SmartScreen may warn because the installer isn't code-signed: *More info → Run anyway*.
**F11** toggles fullscreen TV mode.

## Build from source

Requirements: JDK 21, Android SDK (API 36), internet access to Maven Central/Google Maven.

```bash
# Tests (shared logic + keyboard/remote navigation UI tests)
./gradlew :shared:desktopTest :shared:testDebugUnitTest

# Android
./gradlew :androidApp:assembleDebug            # androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleRelease          # signed if AURORA_KEYSTORE_* are set, else with the debug key
./gradlew :androidApp:bundleRelease            # AAB

# Windows (run on Windows; jpackage can't cross-compile)
#  1. Put libmpv-2.dll in desktopApp/native/windows/
#     (from shinchiro/mpv-winbuild-cmake releases: mpv-dev-x86_64-*.7z)
#     and vulkan-1.dll next to it (Khronos loader, e.g. from the Silk.NET.Vulkan.Loader.Native NuGet package)
./gradlew :desktopApp:packageMsi :desktopApp:packageExe :desktopApp:createDistributable
#  Output: desktopApp/build/compose/binaries/main/{msi,exe,app}

# Run the desktop app directly (Linux/macOS dev needs libmpv installed: apt install libmpv-dev)
./gradlew :desktopApp:run
```

### Release signing

Release APKs are signed from environment variables so no key is ever committed:
`AURORA_KEYSTORE_PATH`, `AURORA_KEYSTORE_PASSWORD`, `AURORA_KEY_ALIAS`, `AURORA_KEY_PASSWORD`.

In CI, add these repository secrets to sign every build with your own stable key (needed for
upgrade-in-place):

| Secret | Value |
|---|---|
| `AURORA_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `AURORA_KEYSTORE_PASSWORD` | keystore password |
| `AURORA_KEY_ALIAS` | key alias |
| `AURORA_KEY_PASSWORD` | key password |

Create a key with `keytool -genkeypair -v -keystore release.jks -alias aurora -keyalg RSA -keysize 4096 -validity 10000`.

## CI

`.github/workflows/build.yml` runs on every push:

1. **Tests + Android:** unit tests, keyboard/D-pad navigation UI tests, debug/release APK, AAB, signature verification.
2. **Real Jellyfin server:** generates a synthetic test library with FFmpeg (H.264+SRT, HEVC Main10+styled ASS with embedded font,
   multi-audio/multi-subtitle MKV, anime-style typesetting series, 4K HDR10, MPEG-4 AVI), starts Jellyfin in Docker,
   runs the integration suite against it, then launches the real desktop build under Xvfb and captures screenshots.
3. **Windows:** fetches libmpv, packages MSI/EXE/portable, runs a headless libmpv + libass self-test of the packaged app over every sample, and takes a launch screenshot.

## Settings

Appearance (theme, animation level, backdrops, carousel, artwork accents) · Playback (max quality,
Direct Play, hardware decoding and passthrough on Windows, audio language, auto-play next, resume
behaviour, skip intervals, intro skipping) · Subtitles (mode, language, size, default delay on Windows,
ASS render mode on TV) · Server (diagnostics, switch user, sign out, add/switch/remove servers) ·
About (version, platform, token storage, detected codec/HDR/audio/subtitle capabilities, licenses).

## License

GPL-3.0 (see `LICENSE`). Aurora TV bundles the Jellyfin Media3 FFmpeg decoder (GPL-3.0) and a GPL
build of libmpv, so the whole app is distributed under the GPL. Other components: Kotlin, kotlinx,
Compose Multiplatform, Ktor, Coil, AndroidX Media3, JNA, the Khronos Vulkan loader (Apache-2.0); the Inter typeface (SIL OFL 1.1, `LICENSES/Inter-OFL.txt`); ass-media (MIT) with libass
(ISC), FreeType (FTL), HarfBuzz (MIT) and FriBidi (LGPL-2.1). See *Settings → About*.

Not affiliated with Apple, Amazon or the Jellyfin project. All UI artwork and icons are original.
