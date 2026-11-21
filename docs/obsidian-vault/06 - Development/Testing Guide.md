---
tags: [development]
---
# Testing Guide

GitHub Actions (`.github/workflows/build.yml`) runs on every push (only `aurora-tv` publishes):

```mermaid
flowchart LR
    A[Tests + Android APK/AAB] --> C[Android TV emulator e2e]
    B[Real Jellyfin: integration tests] --> C
    A --> P[Publish latest-build + ci-results]
    B --> P
    C --> P
```

- **Unit tests** (`shared/src/commonTest`): fake Jellyfin via Ktor `MockEngine`. Covers API, sessions,
  profiles (incl. `LocalProfilesTest` for device-only profiles), playback decisions, skip detection
  (`CreditsVisionTest` for the on-screen outro check), fingerprints, recommendations (incl. the
  Charlotte case), the audio default (`AudioDefaultTest`), temporary files (`EpisodeTempFilesTest`),
  search, stores, updates, VPN address racing, subtitle choice (`SubtitleSelectionTest`: full vs
  Signs & Songs, SDH, only S&S, ambiguous names, dubs, missing metadata, hand-picked S&S on the next
  episode), virtual episodes (`VirtualEpisodesTest`), film subtitle fonts (`SubtitleThemesTest`),
  the recommendation cache (`RecommendationCacheTest`), air dates (`AirDatesTest`: time
  zones, aired/today, placeholders, Trakt merge) and audio edges (`AudioFingerprintTest`). They run on the JVM test host (`:shared:jvmTest`) and
  on Android (`:shared:testDebugUnitTest`).
- **JVM tests** (`jvmTest`): integration tests against a real Jellyfin in Docker (including intro +
  outro by listening on *Wavelength Street*, and the outro found on screen on *Harbor Precinct*, a
  generated live-action series whose credits music changes every episode and whose episode 2 has a
  post-credits scene). The listening check asserts edge accuracy (no story skipped), that Wavelength
  episode 3's credits stop before its 15 s post-credits scene, and logs per-step timings. There's also
  a diagnostic of where the server's mid-file audio starts (0 ms off on CI), a check that the upcoming
  episodes request answers and invents nothing, keyboard navigation UI tests and a ratings UI test.
- **Emulator e2e** (`ci/android_e2e.sh`, `ci/android_release_smoke.sh`): sign-in, D-pad navigation
  with screenshots, playback/resume timings, Home fill time and request counts, Home opened again
  from the side menu three times (`am start --activity-single-top … --es route section:…`, delivered
  through `MainActivity.onNewIntent`), skip-marker timing, and the
  "Layout and UI sounds" block (first-card position, headings, details hero, 3 moves = 3 ticks,
  OK = 1 chime, no sounds in the player, none when switched off).

Results and screenshots are pushed to the `ci-results` branch; builds to the `latest-build` release.

> [!warning] CI has no Fire TV and no GPU. Anything hardware-specific is listed in [[Known Issues]].

## Related notes
[[Build and Run]] · [[Change Log]]
