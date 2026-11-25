---
tags: [development]
---
# Development Setup

- **JDK 21**, Android SDK (compileSdk 36, minSdk 23 / Android 6.0, targetSdk 35).
- Kotlin 2.2.20, Compose Multiplatform 1.9 (Gradle wrapper in the repo).
- IntelliJ IDEA or Android Studio with the Kotlin Multiplatform plugin.
- A Jellyfin 10.9+ server to sign in to; CI starts one in Docker with generated sample media
  (`ci/jellyfin_setup.sh`, `ci/make_samples.sh`).

Branch: `aurora-tv`. Every push runs the full pipeline ([[Testing Guide]]).

## Related notes
[[Build and Run]] · [[Project Structure]]
