---
tags: [development]
---
# Build and Run

| Target | Command |
|---|---|
| Android debug APK | `./gradlew :androidApp:assembleDebug` |
| Android release APK / AAB | `./gradlew :androidApp:assembleRelease :androidApp:bundleRelease` (needs the signing key) |
| Windows MSI / EXE / portable | `./gradlew :desktopApp:packageMsi :desktopApp:packageExe :desktopApp:createDistributable` |
| Desktop app (dev) | `./gradlew :desktopApp:run` |
| Unit tests | `./gradlew :shared:desktopTest :shared:testDebugUnitTest` |

Install on a Fire TV: `adb connect <tv-ip>` then `adb install -r androidApp/build/outputs/apk/release/*.apk`,
or use the in-app updater ([[App Updates]]).

CI launch extras for the Android app (debug/testing only): `--ez traceUi true` (layout/sound trace
logs), `--ei resumeLevel 0..3` (compare resume optimisations).

## Related notes
[[Development Setup]] · [[Testing Guide]]
