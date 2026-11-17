---
tags: [overview, architecture]
---
# Tech Stack

| Layer | Technology | Where |
|---|---|---|
| Language | Kotlin 2.2 (Multiplatform) | everywhere |
| UI | Compose Multiplatform 1.9 | `shared/src/commonMain/.../ui` |
| Networking | Ktor 3 client (OkHttp engine on Android and desktop), kotlinx.serialization | `jellyfin/JellyfinApi.kt`, `jellyfin/HttpEngine.kt` |
| Images | Coil 3 (Ktor fetcher, memory + 300 MB disk cache), BlurHash placeholders | `ui/App.kt`, `ui/components/Artwork.kt`, `ui/theme/BlurHash.kt` |
| Android playback | Media3 ExoPlayer 1.8 + HLS, Jellyfin FFmpeg audio decoder, ass-media (libass), Media3 OkHttp data source, Media3 cache | `androidMain/.../ExoPlayerEngine.kt`, `MediaHeaderCache.kt` |
| Second player | libmpv (Android: `dev.jdtech.mpv`; Windows: libmpv via JNA) | `MpvAndroidEngine.kt`, `desktopMain/.../MpvEngine.kt` |
| Secure storage | Android Keystore (AES-256-GCM); Windows DPAPI | `KeystoreVault.kt`, `DesktopStorage.kt` |
| UI sounds | Android SoundPool; Java Sound clips on desktop | `AndroidUiSounds.kt`, `DesktopUiSounds.kt` |
| Build | Gradle (Kotlin DSL), jpackage for Windows | `build.gradle.kts`, `desktopApp/` |
| CI | GitHub Actions: unit tests, real Jellyfin in Docker, Android TV emulator, Windows runner | `.github/workflows/build.yml`, `ci/` |

No database: the app's own data is small JSON files plus caches (see [[Data Storage]],
[[Caching Strategy]]). Library data always comes from the server.

## Related notes
[[Project Structure]] · [[System Architecture]] · [[Build and Run]]
