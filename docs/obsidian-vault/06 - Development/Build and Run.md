---
tags: [development]
---
# Build and Run

| Target | Command |
|---|---|
| Android debug APK | `./gradlew :androidApp:assembleDebug` |
| Android release APK / AAB | `./gradlew :androidApp:assembleRelease :androidApp:bundleRelease` (needs the signing key) |
| Unit tests (JVM host + Android) | `./gradlew :shared:jvmTest :shared:testDebugUnitTest` |
| Integration tests against a server | `AURORA_IT_URL=http://host:8096 ./gradlew :shared:jvmTest --tests 'com.auroratv.JellyfinIntegrationTest'` |

Install on a Fire TV: `adb connect <tv-ip>` then `adb install -r androidApp/build/outputs/apk/release/*.apk`,
or use the in-app updater ([[App Updates]]).

CI launch extras for the Android app (debug/testing only): `--ez traceUi true` (layout/sound trace
logs), `--ei resumeLevel 0..3` (compare resume optimisations).

## Related notes
[[Development Setup]] · [[Testing Guide]]
