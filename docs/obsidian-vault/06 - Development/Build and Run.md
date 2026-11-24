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


## Version shown in the app (Nov 2026)
CI builds show the commit next to the build number, e.g. **1.0.158 (abc1234)**, in Settings → About
and the update card. The commit comes from `GITHUB_SHA` at build time (manifest meta-data
`com.auroratv.commit`); local builds show the version alone.

## Published files
`latest-build` holds `Sharkfin-release.apk`, `Sharkfin-debug.apk`, `Sharkfin-release.aab` and
`version.json`. The old `Nameless-release.apk` and `AuroraTV-release.apk` copies are no longer
published (from 1.0.158). Builds from before the Sharkfin rename can't update themselves: install
`Sharkfin-release.apk` by hand on those.

## Secrets the build uses
- Release signing (`AURORA_*`, optional).
- `DISC_MENU_TOKEN` (optional): the built-in disc menu token ([[Disc Menus]]).
